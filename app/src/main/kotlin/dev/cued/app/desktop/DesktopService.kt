package dev.cued.app.desktop

import android.app.Activity
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import dev.cued.app.Graph
import dev.cued.app.data.Desktop
import dev.cued.app.data.DesktopStore
import dev.cued.app.data.Friend
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.station.Follow
import dev.cued.app.util.DebugLog
import dev.cued.core.crypto.Nip19
import dev.cued.core.desktop.AccountChain
import dev.cued.core.desktop.Assertion
import dev.cued.core.desktop.DesktopClient
import dev.cued.core.desktop.DesktopException
import dev.cued.core.desktop.FriendLink
import dev.cued.core.desktop.ManifestEntry
import dev.cued.core.desktop.PairLink
import dev.cued.core.desktop.Seal
import dev.cued.core.desktop.SharedSettings
import dev.cued.core.station.Nostr
import dev.cued.core.station.StationTrack
import dev.cued.core.share.SharePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The phone's side of a paired CUEd desktop (CUEd-desktop `docs/protocol.md`):
 * pairing with the account choice, manifest sync with resumable uploads,
 * the account chain, shared settings, friends, and fetching tracks from the
 * desktop library. Writes happen only on the LAN; from outside, reads go
 * through the funnel with a biometric assertion.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DesktopService(private val graph: Graph) {
    val store = DesktopStore(graph.app)
    val bio = BiometricSigner(graph.app)
    private val scope = graph.appScope
    private val station get() = graph.station.store

    data class SyncProgress(val desktopId: String, val phase: String, val done: Int = 0, val total: Int = 0, val note: String = "")
    private val _progress = MutableStateFlow<SyncProgress?>(null)
    /** What the current sync is doing, for the Desktop screen; null when idle. */
    val progress: StateFlow<SyncProgress?> = _progress

    /** `<challenge>.<sig>` per desktop with its expiry, so a biometric unlock lasts a few minutes of browsing. */
    private val assertions = HashMap<String, Pair<String, Long>>()

    // ---- reaching a desktop ------------------------------------------------------------------

    /**
     * A client for [d] through the first address that answers: the last good
     * one, the LAN addresses from the pairing code, then the funnel (pinned
     * certificate, biometric assertion through [activity] when one is needed).
     */
    suspend fun connect(d: Desktop, activity: Activity? = null): DesktopClient = withContext(Dispatchers.IO) {
        val order = (listOfNotNull(d.lastGoodBase) + d.addresses).distinct()
        for (base in order) {
            if (base.startsWith("https://", true)) continue
            val c = DesktopClient(base, d.token)
            if (reachable(c)) { if (base != d.lastGoodBase) store.upsertDesktop(d.copy(lastGoodBase = base)); return@withContext c }
        }
        val funnel = d.funnel ?: throw java.io.IOException("${d.label} is not on this Wi-Fi and has no funnel address")
        val c = DesktopClient(funnel, d.token, d.cert)
        c.assertion = assertions[d.id]?.takeIf { it.second > System.currentTimeMillis() }?.first
        if (c.assertion == null) {
            val act = activity ?: throw java.io.IOException("${d.label} is only reachable from outside: open this screen again to unlock with your fingerprint or face")
            val challenge = c.challenge()
            val header = bio.assertion(act, store.deviceId(), challenge).getOrElse { throw java.io.IOException(it.message ?: "Biometric unlock failed") }
            assertions[d.id] = header to (System.currentTimeMillis() + Assertion.LIFETIME_MS)
            c.assertion = header
        }
        if (!reachable(c)) throw java.io.IOException("${d.label} did not answer through the funnel")
        c
    }

    private fun reachable(c: DesktopClient): Boolean = runCatching { c.me(); true }.getOrElse { it is DesktopException && it.code != 401 }

    // ---- pairing ---------------------------------------------------------------------------

    /**
     * Pairs with the desktop behind [link]. [account] is `join` (take the
     * desktop's account), `keep` (give it this phone's) or `separate`.
     */
    suspend fun pair(link: PairLink, account: String, activity: Activity? = null): Result<Desktop> = withContext(Dispatchers.IO) {
        runCatching {
            val device = store.deviceId()
            val salt = store.deviceSalt()
            val name = store.phoneName.first()
            val identity = station.ensureIdentity()
            val biokey = bio.publicKeyHex().orEmpty()
            val devhash = AccountChain.devhash(device, salt)
            val req = link.request(name, device, pubkey = identity.pubkeyHex, account = account, biokey = biokey, devhash = devhash)
            var resp: JsonObject? = null
            var base: String? = null
            var lastError: Throwable? = null
            for (addr in link.addresses) {
                try { resp = DesktopClient(addr).pair(req); base = addr; break } catch (e: DesktopException) { throw e } catch (e: Exception) { lastError = e }
            }
            if (resp == null) {
                // Pairing is a write: the desktop only accepts it on its own LAN, so the funnel is not tried.
                throw java.io.IOException(lastError?.message ?: "No address in the code answered. Pair on the desktop's own Wi-Fi.")
            }
            val token = resp["token"]?.jsonPrimitive?.contentOrNull ?: error("no token in the answer")
            val info = resp["desktop"] as? JsonObject
            val desktopPub = info?.get("pubkey")?.jsonPrimitive?.contentOrNull ?: link.pubkey.orEmpty()
            val d = Desktop(
                id = desktopPub.ifBlank { PairLink.randomHex(8) }, name = info?.get("name")?.jsonPrimitive?.contentOrNull ?: link.name,
                addresses = link.addresses, funnel = link.funnel, cert = link.certSha256, token = token,
                relay = info?.get("relay")?.jsonPrimitive?.contentOrNull ?: link.relay, pubkey = desktopPub, account = account,
                pairedAt = System.currentTimeMillis(), lastGoodBase = base,
            )
            store.upsertDesktop(d)
            val client = DesktopClient(base!!, token)
            val secret = hexToBytes(link.secret)
            when (account) {
                "join" -> {
                    val sealed = (resp["account"] as? JsonObject)?.get("sealed")?.jsonPrimitive?.contentOrNull ?: error("the desktop sent no account to join")
                    val bytes = Seal.open(secret, "account", sealed) ?: error("could not open the account bundle (wrong code?)")
                    val bundle = Nostr.json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
                    val secretHex = bundle["secret_hex"]?.jsonPrimitive?.contentOrNull ?: error("bundle without a key")
                    val chain = AccountChain.decode(Nostr.json.encodeToString(JsonObject.serializer(), bundle["chain"]!!.jsonObject)) ?: error("bundle without a chain")
                    chain.validate()?.let { error("account chain: $it") }
                    station.importSecret(secretHex).getOrThrow()
                    store.setChainJson(chain.encode())
                    DebugLog.i(TAG, "joined account ${Nip19.npub(chain.pubkey.orEmpty()).take(16)}")
                }
                "keep" -> {
                    val chain = ensureChain(name, devhash)
                    val bundle = """{"secret_hex":${DesktopClient.quote(identity.secretHex)},"chain":${chain.encode()}}"""
                    val sealed = Seal.seal(secret, "account", bundle.toByteArray(Charsets.UTF_8))
                    client.importAccount(sealed)
                    DebugLog.i(TAG, "desktop now uses this phone's account")
                }
                else -> {}
            }
            runCatching { pullShared(client, d) }
            runCatching { syncFriends(client) }
            scope.launch { sync(d) }
            d
        }
    }

    suspend fun unpair(d: Desktop) {
        store.removeDesktop(d.id)
        d.relay?.let { relay -> station.setRelays(station.relays.first().filter { it != relay }) }
    }

    // ---- the account chain ---------------------------------------------------------------------

    /** This phone's copy of the chain, created from the station identity when there is none yet. */
    suspend fun ensureChain(phoneName: String? = null, devhash: String? = null): AccountChain {
        val identity = station.ensureIdentity()
        val existing = store.chainJson.first()?.let { AccountChain.decode(it) }?.takeIf { it.validate() == null && it.pubkey == identity.pubkeyHex }
        if (existing != null) return existing
        val name = phoneName ?: store.phoneName.first()
        val dh = devhash ?: AccountChain.devhash(store.deviceId(), store.deviceSalt())
        val now = System.currentTimeMillis() / 1000
        val chain = AccountChain.create(identity.secretHex, station.name.first().ifBlank { name }, now)
            .append(identity.secretHex, "bind_device", AccountChain.data("""{"devhash":${DesktopClient.quote(dh)},"name":${DesktopClient.quote(name)},"kind":"phone"}"""), now)
        store.setChainJson(chain.encode())
        return chain
    }

    /** Exchanges chains with the desktop: the longer valid copy wins on both sides. */
    private suspend fun mergeChain(client: DesktopClient) {
        val identity = station.ensureIdentity()
        val theirs = runCatching { AccountChain.decode(Nostr.json.encodeToString(JsonObject.serializer(), client.chain())) }.getOrNull() ?: return
        if (theirs.pubkey != identity.pubkeyHex) return // separate accounts: nothing to merge
        val mine = ensureChain()
        val merged = mine.merge(theirs)
        if (merged != mine) store.setChainJson(merged.encode())
        if (merged.height > theirs.height) runCatching { client.mergeChain(merged.encode()) }
    }

    // ---- shared settings --------------------------------------------------------------------

    /** Reads the account's shared settings from [client] and applies the ones the phone understands. */
    private suspend fun pullShared(client: DesktopClient, d: Desktop) {
        val s = client.shared()
        store.setSharedJson("""{"relayEnabled":${s.relayEnabled},"friendStreaming":${s.friendStreaming},"stationName":${DesktopClient.quote(s.stationName)},"height":${s.height}}""")
        applyShared(s, d.relay)
    }

    private suspend fun applyShared(s: SharedSettings, relayUrl: String?) {
        if (s.theme.isNotEmpty()) {
            val cur = graph.settings.theme.first()
            val merged = s.theme.entries.fold(cur) { acc, (k, v) -> dev.cued.app.ui.theme.ThemeColors.normalise(v)?.let { acc.with(k, it) } ?: acc }
            if (merged != cur) graph.settings.setTheme(merged)
        }
        if (relayUrl != null) {
            val relays = station.relays.first()
            if (s.relayEnabled && relayUrl !in relays) station.setRelays(listOf(relayUrl) + relays)
            if (!s.relayEnabled && relayUrl in relays) station.setRelays(relays.filter { it != relayUrl })
        }
        if (s.stationName.isNotBlank() && station.name.first().isBlank()) station.setName(s.stationName)
    }

    /** Changes a shared setting on the desktop (LAN) and applies it here. */
    suspend fun putShared(d: Desktop, patchJson: String): Result<SharedSettings> = withContext(Dispatchers.IO) {
        runCatching {
            val c = connect(d)
            if (c.remote) error("Shared settings change only on the desktop's Wi-Fi")
            val s = c.putShared(patchJson)
            applyShared(s, d.relay)
            s
        }
    }

    // ---- sync --------------------------------------------------------------------------------

    fun syncAsync(d: Desktop) { scope.launch { sync(d) } }

    /** Manifest, uploads of what the desktop lacks, chain merge, shared settings, friends. LAN only. */
    suspend fun sync(d: Desktop, activity: Activity? = null): Result<String> = withContext(Dispatchers.IO) {
        val r = runCatching {
            _progress.value = SyncProgress(d.id, "Connecting")
            val client = connect(d, activity)
            if (client.remote) error("Backup only runs on the desktop's Wi-Fi; from here the desktop is read-only")
            _progress.value = SyncProgress(d.id, "Hashing the library")
            val entries = manifest { done, total -> _progress.value = SyncProgress(d.id, "Hashing the library", done, total) }
            _progress.value = SyncProgress(d.id, "Comparing", 0, entries.size)
            val result = client.manifest(entries)
            val byHash = entries.associateBy { it.sha256 }
            var sent = 0
            var failed = 0
            for ((i, sha) in result.missing.withIndex()) {
                val e = byHash[sha] ?: continue
                _progress.value = SyncProgress(d.id, "Uploading", i, result.missing.size, "${e.artist} – ${e.title}")
                val track = graph.db.tracks().byId(e.key.toLongOrNull() ?: -1) ?: continue
                try {
                    val ok = client.upload(e, e.size, open = { off -> openAt(track, off) }) { bytes -> _progress.value = SyncProgress(d.id, "Uploading", i, result.missing.size, "${e.artist} – ${e.title} · ${bytes * 100 / e.size.coerceAtLeast(1)}%") }
                    if (ok) sent++ else failed++
                } catch (ex: Exception) {
                    failed++; DebugLog.w(TAG, "upload ${e.title}", ex)
                }
            }
            _progress.value = SyncProgress(d.id, "Account and settings")
            runCatching { mergeChain(client) }.onFailure { DebugLog.w(TAG, "chain", it) }
            runCatching { pullShared(client, d) }.onFailure { DebugLog.w(TAG, "shared", it) }
            runCatching { syncFriends(client) }.onFailure { DebugLog.w(TAG, "friends", it) }
            val note = "${entries.size} on phone · ${result.known + sent} backed up" + (if (failed > 0) " · $failed failed" else "")
            store.upsertDesktop((store.desktop(d.id) ?: d).copy(lastSyncAt = System.currentTimeMillis(), lastSyncNote = note))
            note
        }
        r.onFailure { e -> DebugLog.w(TAG, "sync ${d.label}", e); store.upsertDesktop((store.desktop(d.id) ?: d).copy(lastSyncNote = "Failed: ${e.message}")) }
        _progress.value = null
        r
    }

    /** Every music track as a manifest line, hashing only files that changed since last time. */
    private suspend fun manifest(onProgress: (Int, Int) -> Unit): List<ManifestEntry> {
        val tracks = graph.db.tracks().all().filter { !it.missing && it.kind == TrackEntity.KIND_MUSIC }
        val genres = graph.library.genreMap.first()
        val cache = HashMap(store.hashCache())
        val out = ArrayList<ManifestEntry>(tracks.size)
        for ((i, t) in tracks.withIndex()) {
            val f = t.path?.let(::File)?.takeIf { it.exists() }
            val size = f?.length() ?: sizeOf(t) ?: continue
            val modified = f?.lastModified() ?: 0L
            val stamp = "$size:$modified:"
            val cached = cache[t.id.toString()]
            val sha = if (cached != null && cached.startsWith(stamp)) cached.removePrefix(stamp) else {
                val h = runCatching { sha256(t) }.getOrNull() ?: continue
                cache[t.id.toString()] = stamp + h
                h
            }
            out += ManifestEntry(
                key = t.id.toString(), title = t.title, artist = t.artist, album = t.album, durationMs = t.durationMs, size = size, sha256 = sha,
                mime = mimeOf(t), genres = genres[t.id].orEmpty(), bpm = t.bpm, link = t.sourceLink, modified = modified,
            )
            if (i % 10 == 0) onProgress(i, tracks.size)
        }
        store.putHashCache(cache.filterKeys { k -> tracks.any { it.id.toString() == k } })
        return out
    }

    private fun sizeOf(t: TrackEntity): Long? = runCatching { graph.app.contentResolver.openFileDescriptor(Uri.parse(t.uri), "r")?.use { it.statSize } }.getOrNull()?.takeIf { it > 0 }

    private fun mimeOf(t: TrackEntity): String = runCatching { graph.app.contentResolver.getType(Uri.parse(t.uri)) }.getOrNull()
        ?: when (t.path?.substringAfterLast('.', "")?.lowercase()) { "m4a", "mp4" -> "audio/mp4"; "flac" -> "audio/flac"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; else -> "audio/mpeg" }

    private fun openStream(t: TrackEntity): InputStream =
        t.path?.let(::File)?.takeIf { it.exists() }?.inputStream() ?: graph.app.contentResolver.openInputStream(Uri.parse(t.uri)) ?: error("cannot open ${t.title}")

    private fun openAt(t: TrackEntity, offset: Long): InputStream = openStream(t).also { s -> var left = offset; while (left > 0) { val n = s.skip(left); if (n <= 0) break; left -= n } }

    private fun sha256(t: TrackEntity): String {
        val md = MessageDigest.getInstance("SHA-256")
        openStream(t).use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- friends -----------------------------------------------------------------------------

    /** This person's friend code: the account's public key, name and relays (never the private key). */
    suspend fun myFriendLink(): FriendLink {
        val identity = station.ensureIdentity()
        val name = station.name.first().ifBlank { store.phoneName.first() }
        return FriendLink(identity.pubkeyHex, name, station.relays.first().take(3))
    }

    /**
     * Scanning someone's code, pasting their npub, or a share that carried a
     * request: sends a request, or accepts when they already asked.
     */
    suspend fun addFriend(text: String, name: String = ""): Result<Friend> = runCatching {
        val t = text.trim()
        val (pubkey, linkName, relays) = FriendLink.decode(t)?.let { Triple(it.pubkey, it.name, it.relays) }
            ?: SharePayload.decode(t)?.friendPubkey?.let { Triple(it, SharePayload.decode(t)?.friendName.orEmpty(), emptyList()) }
            ?: Nip19.parse(t)?.takeIf { it.first != "nsec" }?.let { Triple(it.second, "", emptyList()) }
            ?: error("Not a friend code, a share with a friend request, or an npub")
        if (pubkey == station.ensureIdentity().pubkeyHex) error("That is your own account")
        val existing = store.friends.first().firstOrNull { it.pubkey == pubkey }
        val status = when (existing?.status) { "friends" -> "friends"; "pending_in" -> "friends"; else -> "pending_out" }
        val f = Friend(pubkey, name.ifBlank { linkName }, status, relays)
        store.upsertFriend(f)
        if (status == "friends") becameFriends(f)
        publishFriendEvent(pubkey, if (status == "friends") "accept" else "request")
        pushToDesktops { c -> c.addFriend(FriendLink(pubkey, f.name, relays).encode()) }
        store.friends.first().first { it.pubkey == pubkey }
    }

    suspend fun acceptFriend(pubkey: String): Result<Friend> = runCatching {
        val existing = store.friends.first().firstOrNull { it.pubkey == pubkey } ?: error("No request from that key")
        val f = existing.copy(status = "friends")
        store.upsertFriend(f)
        becameFriends(f)
        publishFriendEvent(pubkey, "accept")
        pushToDesktops { c -> c.acceptFriend(pubkey) }
        f
    }

    suspend fun removeFriend(pubkey: String) {
        store.removeFriend(pubkey)
        publishFriendEvent(pubkey, "remove")
        pushToDesktops { c -> c.removeFriend(pubkey) }
    }

    /** A share arrived with `fr`/`fn`: remember the request so the Friends screen can accept it. */
    suspend fun noteIncomingRequest(pubkey: String, name: String) {
        if (pubkey == station.ensureIdentity().pubkeyHex) return
        val existing = store.friends.first().firstOrNull { it.pubkey == pubkey }
        when (existing?.status) {
            "friends" -> {}
            "pending_out" -> { val f = existing.copy(status = "friends", name = existing.name.ifBlank { name }); store.upsertFriend(f); becameFriends(f); publishFriendEvent(pubkey, "accept") }
            else -> store.upsertFriend(Friend(pubkey, name, "pending_in"))
        }
    }

    /** Friends are followed as stations, so Following shows them at once. */
    private suspend fun becameFriends(f: Friend) {
        station.addFollow(Follow(pubkey = f.pubkey, name = f.name, relays = f.relays))
    }

    /** Kind-30778 request/accept/remove over the relays, when Stations is on (it holds the sockets). */
    private suspend fun publishFriendEvent(target: String, status: String) {
        if (!station.enabled.first()) return
        runCatching {
            val identity = station.ensureIdentity()
            val name = station.name.first().ifBlank { store.phoneName.first() }
            val content = """{"v":1,"name":${DesktopClient.quote(name)},"status":"$status","ts":${System.currentTimeMillis() / 1000}}"""
            val ev = Nostr.sign(identity.secretHex, FriendLink.KIND, FriendLink.tags(target), content)
            val client = graph.station.client
            client.acquire(USER)
            client.publish(ev)
            delay(1_500)
            client.release(USER)
        }.onFailure { DebugLog.w(TAG, "friend event", it) }
    }

    /** Runs [block] against every paired desktop reachable on the LAN; failures are logged, never fatal. */
    private suspend fun pushToDesktops(block: suspend (DesktopClient) -> Unit) = withContext(Dispatchers.IO) {
        for (d in store.desktops.first()) {
            runCatching { val c = connect(d); if (!c.remote) block(c) }.onFailure { DebugLog.d(TAG, "friends push to ${d.label}: ${it.message}") }
        }
    }

    /** Desktop ↔ phone friend lists: the desktop learns what the phone did; `friends` on either side wins. */
    private suspend fun syncFriends(client: DesktopClient) {
        val local = store.friends.first()
        val remote = client.friends().map { it.jsonObject }
        val remoteByKey = remote.associateBy { it["pubkey"]!!.jsonPrimitive.content }
        for (f in local) {
            val r = remoteByKey[f.pubkey]
            when {
                r == null -> if (f.status != "pending_in") runCatching { client.addFriend(FriendLink(f.pubkey, f.name, f.relays).encode()) }
                r["status"]?.jsonPrimitive?.contentOrNull == "pending_in" && f.status == "friends" -> runCatching { client.acceptFriend(f.pubkey) }
            }
        }
        val localByKey = local.associateBy { it.pubkey }
        for (r in remote) {
            val pk = r["pubkey"]!!.jsonPrimitive.content
            val status = r["status"]?.jsonPrimitive?.contentOrNull ?: continue
            val name = r["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val mine = localByKey[pk]
            if (mine == null || (status == "friends" && mine.status != "friends") || (mine.status == "pending_out" && status == "pending_in")) {
                val f = Friend(pk, name, if (mine?.status == "pending_out" && status == "pending_in") "friends" else status)
                store.upsertFriend(f)
                if (f.status == "friends") becameFriends(f)
            }
        }
    }

    // ---- the desktop library -------------------------------------------------------------------

    data class DesktopTrack(val sha256: String, val title: String, val artist: String, val album: String, val durationMs: Long, val size: Long, val bpm: Float?, val link: String?)

    suspend fun library(d: Desktop, query: String, activity: Activity?): Result<List<DesktopTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val c = connect(d, activity)
            c.library(query).let { o ->
                o["tracks"]?.jsonArray?.map { it.jsonObject }?.map { t ->
                    DesktopTrack(
                        sha256 = t["sha256"]!!.jsonPrimitive.content, title = t["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), artist = t["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        album = t["album"]?.jsonPrimitive?.contentOrNull.orEmpty(), durationMs = t["durationMs"]?.jsonPrimitive?.longOrNull ?: 0, size = t["size"]?.jsonPrimitive?.longOrNull ?: 0,
                        bpm = (t["bpm"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull(), link = t["link"]?.jsonPrimitive?.contentOrNull,
                    )
                } ?: emptyList()
            }
        }
    }

    /**
     * Streams a desktop track into the temporary cache (kept 48 hours, hidden
     * from the library) and returns the row to play. A copy already in the
     * library plays from there.
     */
    suspend fun fetchForPlayback(d: Desktop, t: DesktopTrack, activity: Activity?): Result<TrackEntity> = withContext(Dispatchers.IO) {
        runCatching {
            graph.db.tracks().byTitleArtist(t.title, t.artist)?.let { if (it.kind != TrackEntity.KIND_STATION || (it.path != null && File(it.path).exists())) return@runCatching it }
            val c = connect(d, activity)
            val entry = StationTrack(title = t.title, artist = t.artist, album = t.album, durationMs = t.durationMs, link = t.link, key = "desktop:${t.sha256}", stream = c.trackUrl(t.sha256))
            // Through the client, so the desktop's certificate pin and the assertion apply from outside the LAN.
            val id = graph.station.cache.fetchFromUrl(entry, c.trackUrl(t.sha256)) { url -> c.open(url) }
            graph.db.tracks().byId(id) ?: error("cached row vanished")
        }
    }

    companion object {
        private const val TAG = "Desktop"
        private const val USER = "friends"
        fun hexToBytes(s: String): ByteArray = ByteArray(s.length / 2) { i -> ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte() }
    }
}
