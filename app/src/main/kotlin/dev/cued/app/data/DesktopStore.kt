package dev.cued.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.desktopStore: DataStore<Preferences> by preferencesDataStore(name = "desktop")

/** A paired CUEd desktop (CUEd-desktop `docs/protocol.md` §2). Lives only on this phone. */
@Serializable
data class Desktop(
    /** The desktop's account pubkey when known, else a random id. */
    val id: String,
    val name: String,
    /** LAN base URLs from the pairing code, in order. */
    val addresses: List<String>,
    /** Funnel base URL (`https://…`) and the certificate fingerprint it is pinned to. */
    val funnel: String? = null,
    val cert: String? = null,
    val token: String,
    val relay: String? = null,
    val pubkey: String = "",
    /** `join`, `keep` or `separate`, as chosen at pairing. */
    val account: String = "separate",
    val pairedAt: Long = 0,
    val lastSyncAt: Long = 0,
    val lastSyncNote: String = "",
    /** Last address that answered, so the next call tries it first. */
    val lastGoodBase: String? = null,
) {
    val label: String get() = name.ifBlank { addresses.firstOrNull() ?: funnel ?: id.take(12) }
}

/** Someone you chose to trust with your station and presence (§9). Only their public key and a name. */
@Serializable
data class Friend(
    val pubkey: String,
    val name: String = "",
    /** `pending_out`, `pending_in` or `friends`. */
    val status: String = "pending_out",
    val relays: List<String> = emptyList(),
    val addedAt: Long = 0,
) {
    val label: String get() = name.ifBlank { dev.cued.core.crypto.Nip19.npub(pubkey).let { it.take(12) + "…" + it.takeLast(6) } }
}

/**
 * Everything the desktop link keeps on the phone: paired desktops and their
 * tokens, this phone's device id and salt, the account chain, the friends
 * list, the last shared settings, and a per-track SHA-256 cache so a
 * manifest does not re-hash the whole library every time.
 */
class DesktopStore(private val context: Context) {
    private object K {
        val desktops = stringPreferencesKey("desktops_json")
        val friends = stringPreferencesKey("friends_json")
        val device = stringPreferencesKey("device_id")
        val salt = stringPreferencesKey("device_salt")
        val chain = stringPreferencesKey("chain_json")
        val hashes = stringPreferencesKey("hash_cache_json")
        val shared = stringPreferencesKey("shared_json")
        val phoneName = stringPreferencesKey("phone_name")
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val desktopsSer = ListSerializer(Desktop.serializer())
    private val friendsSer = ListSerializer(Friend.serializer())
    private val mapSer = MapSerializer(String.serializer(), String.serializer())

    val desktops: Flow<List<Desktop>> = context.desktopStore.data.map { p -> p[K.desktops]?.let { runCatching { json.decodeFromString(desktopsSer, it) }.getOrNull() } ?: emptyList() }
    val friends: Flow<List<Friend>> = context.desktopStore.data.map { p -> p[K.friends]?.let { runCatching { json.decodeFromString(friendsSer, it) }.getOrNull() } ?: emptyList() }
    /** The account chain as JSON (`{"blocks":[…]}`), or null before any account joined or was shared. */
    val chainJson: Flow<String?> = context.desktopStore.data.map { it[K.chain] }
    /** Last shared settings seen on a desktop, as JSON. */
    val sharedJson: Flow<String?> = context.desktopStore.data.map { it[K.shared] }
    val phoneName: Flow<String> = context.desktopStore.data.map { it[K.phoneName] ?: defaultName() }

    private fun defaultName(): String = (android.os.Build.MODEL ?: "Android phone").take(40)

    /** A stable 16-hex id for this install, made on first use. */
    suspend fun deviceId(): String {
        context.desktopStore.data.first()[K.device]?.let { return it }
        val id = dev.cued.core.desktop.PairLink.randomHex(8)
        context.desktopStore.edit { it[K.device] = id }
        return id
    }

    /** The per-install salt behind this phone's device hash in the account chain. */
    suspend fun deviceSalt(): String {
        context.desktopStore.data.first()[K.salt]?.let { return it }
        val s = dev.cued.core.desktop.PairLink.randomHex(16)
        context.desktopStore.edit { it[K.salt] = s }
        return s
    }

    suspend fun setPhoneName(n: String) = context.desktopStore.edit { it[K.phoneName] = n.trim().take(40) }

    suspend fun upsertDesktop(d: Desktop) {
        val list = desktops.first().filter { it.id != d.id } + d
        context.desktopStore.edit { it[K.desktops] = json.encodeToString(desktopsSer, list) }
    }

    suspend fun removeDesktop(id: String) {
        val list = desktops.first().filter { it.id != id }
        context.desktopStore.edit { it[K.desktops] = json.encodeToString(desktopsSer, list) }
    }

    suspend fun desktop(id: String): Desktop? = desktops.first().firstOrNull { it.id == id }

    suspend fun setFriends(list: List<Friend>) = context.desktopStore.edit { it[K.friends] = json.encodeToString(friendsSer, list) }

    suspend fun upsertFriend(f: Friend) {
        val cur = friends.first()
        val prev = cur.firstOrNull { it.pubkey == f.pubkey }
        val merged = f.copy(name = f.name.ifBlank { prev?.name.orEmpty() }, relays = f.relays.ifEmpty { prev?.relays.orEmpty() }, addedAt = prev?.addedAt?.takeIf { it > 0 } ?: f.addedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
        setFriends(cur.filter { it.pubkey != f.pubkey } + merged)
    }

    suspend fun removeFriend(pubkey: String) = setFriends(friends.first().filter { it.pubkey != pubkey })

    suspend fun setChainJson(text: String?) = context.desktopStore.edit { if (text == null) it.remove(K.chain) else it[K.chain] = text }
    suspend fun setSharedJson(text: String) = context.desktopStore.edit { it[K.shared] = text }

    /** track id → `size:modified:sha256`, so unchanged files are never re-hashed. */
    suspend fun hashCache(): Map<String, String> = context.desktopStore.data.first()[K.hashes]?.let { runCatching { json.decodeFromString(mapSer, it) }.getOrNull() } ?: emptyMap()
    suspend fun putHashCache(map: Map<String, String>) = context.desktopStore.edit { it[K.hashes] = json.encodeToString(mapSer, map) }

    /** Forgets desktops, friends, chain and caches; the device id and salt stay so a re-pair binds the same device. */
    suspend fun reset() = context.desktopStore.edit { p -> listOf(K.desktops, K.friends, K.chain, K.hashes, K.shared).forEach { p.remove(it) } }
}
