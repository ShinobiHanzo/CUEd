package dev.cued.app.station

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.cued.core.crypto.Nip19
import dev.cued.core.crypto.Secp256k1
import dev.cued.core.crypto.hexToBytes
import dev.cued.core.crypto.toHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.stationStore: DataStore<Preferences> by preferencesDataStore(name = "station")

/** Someone whose station this phone follows. Lives only on this phone. */
@Serializable
data class Follow(val pubkey: String, val name: String = "", val relays: List<String> = emptyList(), val addedAt: Long = 0) {
    val shortId: String get() = Nip19.npub(pubkey).let { it.take(12) + "…" + it.takeLast(6) }
    val label: String get() = name.ifBlank { shortId }
}

/** The phone's own key pair: the identity behind its station. Never leaves the phone unless exported on purpose. */
data class Identity(val secretHex: String, val pubkeyHex: String) {
    val npub: String get() = Nip19.npub(pubkeyHex)
    val nsec: String get() = Nip19.nsec(secretHex)
}

class StationStore(private val context: Context) {
    private object K {
        val secret = stringPreferencesKey("secret_hex")
        val name = stringPreferencesKey("name")
        val follows = stringPreferencesKey("follows_json")
        val relays = stringPreferencesKey("relays_json")
        val prefetchMobile = booleanPreferencesKey("prefetch_on_mobile")
        val cacheMb = intPreferencesKey("cache_mb")
    }
    private val json = Json { ignoreUnknownKeys = true }

    val identity: Flow<Identity?> = context.stationStore.data.map { p -> p[K.secret]?.let { Identity(it, Secp256k1.publicKey(it.hexToBytes()).toHex()) } }
    val name: Flow<String> = context.stationStore.data.map { it[K.name].orEmpty() }
    val follows: Flow<List<Follow>> = context.stationStore.data.map { p -> p[K.follows]?.let { runCatching { json.decodeFromString(ListSerializer(Follow.serializer()), it) }.getOrNull() } ?: emptyList() }
    val relays: Flow<List<String>> = context.stationStore.data.map { p -> p[K.relays]?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_RELAYS }
    val prefetchOnMobile: Flow<Boolean> = context.stationStore.data.map { it[K.prefetchMobile] ?: true }
    val cacheMb: Flow<Int> = context.stationStore.data.map { it[K.cacheMb] ?: 300 }

    /** Creates the key pair on first use. */
    suspend fun ensureIdentity(): Identity {
        identity.first()?.let { return it }
        val sk = Secp256k1.generateSecret().toHex()
        context.stationStore.edit { it[K.secret] = sk }
        return Identity(sk, Secp256k1.publicKey(sk.hexToBytes()).toHex())
    }

    /** Accepts an nsec or 64-char hex secret. Replaces the current identity. */
    suspend fun importSecret(text: String): Result<Identity> = runCatching {
        val (kind, hex) = Nip19.parse(text) ?: error("Not an nsec or hex key")
        if (kind == "npub") error("That is a public key (npub); the secret key starts with nsec")
        require(Secp256k1.isValidSecret(hex.hexToBytes())) { "Not a valid secret key" }
        context.stationStore.edit { it[K.secret] = hex }
        Identity(hex, Secp256k1.publicKey(hex.hexToBytes()).toHex())
    }

    suspend fun setName(n: String) = context.stationStore.edit { it[K.name] = n.trim().take(40) }

    suspend fun addFollow(f: Follow) {
        val list = follows.first().filter { it.pubkey != f.pubkey } + f.copy(addedAt = if (f.addedAt == 0L) System.currentTimeMillis() else f.addedAt)
        context.stationStore.edit { it[K.follows] = json.encodeToString(ListSerializer(Follow.serializer()), list) }
    }

    suspend fun removeFollow(pubkey: String) {
        val list = follows.first().filter { it.pubkey != pubkey }
        context.stationStore.edit { it[K.follows] = json.encodeToString(ListSerializer(Follow.serializer()), list) }
    }

    suspend fun setRelays(urls: List<String>) {
        val clean = urls.map { it.trim() }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct()
        context.stationStore.edit { if (clean.isEmpty()) it.remove(K.relays) else it[K.relays] = json.encodeToString(ListSerializer(String.serializer()), clean) }
    }

    suspend fun setPrefetchOnMobile(on: Boolean) = context.stationStore.edit { it[K.prefetchMobile] = on }
    suspend fun setCacheMb(mb: Int) = context.stationStore.edit { it[K.cacheMb] = mb.coerceIn(50, 4000) }

    companion object {
        /** Public, free, no-account relays. Any of them can be swapped for your own in the station settings. */
        val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")
    }
}
