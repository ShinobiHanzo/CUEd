package dev.cued.core.station

import dev.cued.core.crypto.Secp256k1
import dev.cued.core.crypto.hexToBytes
import dev.cued.core.crypto.toHex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The thin slice of the Nostr protocol (NIP-01) a station needs: signed
 * events, replaceable by kind + author + `d` tag, carried by any relay. Keys
 * are the person's identity; relays are dumb, interchangeable and
 * self-hostable, which is why they fit a self-custody player.
 */
@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
}

sealed class RelayMessage {
    data class Event(val subId: String, val event: NostrEvent) : RelayMessage()
    data class Eose(val subId: String) : RelayMessage()
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage()
    data class Closed(val subId: String, val message: String) : RelayMessage()
    data class Notice(val message: String) : RelayMessage()
    data class Unknown(val raw: String) : RelayMessage()
}

object Nostr {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** NIP-01 canonical form hashed for the id: `[0, pubkey, created_at, kind, tags, content]`. */
    fun canonical(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
        json.encodeToString(buildJsonArray {
            add(JsonPrimitive(0)); add(JsonPrimitive(pubkey)); add(JsonPrimitive(createdAt)); add(JsonPrimitive(kind))
            add(JsonArray(tags.map { t -> JsonArray(t.map(::JsonPrimitive)) })); add(JsonPrimitive(content))
        })

    fun id(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
        Secp256k1.sha256(canonical(pubkey, createdAt, kind, tags, content).toByteArray(Charsets.UTF_8)).toHex()

    fun sign(secretHex: String, kind: Int, tags: List<List<String>>, content: String, createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent {
        val sk = secretHex.hexToBytes()
        val pub = Secp256k1.publicKey(sk).toHex()
        val id = id(pub, createdAt, kind, tags, content)
        val sig = Secp256k1.sign(id.hexToBytes(), sk).toHex()
        return NostrEvent(id, pub, createdAt, kind, tags, content, sig)
    }

    fun verify(e: NostrEvent): Boolean = runCatching {
        e.id == id(e.pubkey, e.created_at, e.kind, e.tags, e.content) &&
            Secp256k1.verify(e.id.hexToBytes(), e.pubkey.hexToBytes(), e.sig.hexToBytes())
    }.getOrDefault(false)

    fun eventMessage(e: NostrEvent): String = json.encodeToString(buildJsonArray { add(JsonPrimitive("EVENT")); add(json.encodeToJsonElement(NostrEvent.serializer(), e)) })
    fun reqMessage(subId: String, vararg filters: JsonObject): String = json.encodeToString(buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(subId)); filters.forEach { add(it) } })
    fun closeMessage(subId: String): String = json.encodeToString(buildJsonArray { add(JsonPrimitive("CLOSE")); add(JsonPrimitive(subId)) })

    fun parse(text: String): RelayMessage = runCatching {
        val arr: JsonArray = json.parseToJsonElement(text).jsonArray
        when (arr[0].jsonPrimitive.content) {
            "EVENT" -> RelayMessage.Event(arr[1].jsonPrimitive.content, json.decodeFromJsonElement(NostrEvent.serializer(), arr[2]))
            "EOSE" -> RelayMessage.Eose(arr[1].jsonPrimitive.content)
            "OK" -> RelayMessage.Ok(arr[1].jsonPrimitive.content, arr[2].jsonPrimitive.boolean, arr.getOrNull(3)?.jsonPrimitive?.content ?: "")
            "CLOSED" -> RelayMessage.Closed(arr[1].jsonPrimitive.content, arr.getOrNull(2)?.jsonPrimitive?.content ?: "")
            "NOTICE" -> RelayMessage.Notice(arr.getOrNull(1)?.jsonPrimitive?.content ?: "")
            else -> RelayMessage.Unknown(text)
        }
    }.getOrElse { RelayMessage.Unknown(text) }

    /** Filter for the latest station state of these authors. */
    fun stationFilter(authors: List<String>, since: Long? = null): JsonObject = JsonObject(buildMap<String, JsonElement> {
        put("kinds", JsonArray(listOf(JsonPrimitive(Station.KIND))))
        put("authors", JsonArray(authors.map(::JsonPrimitive)))
        put("#d", JsonArray(listOf(JsonPrimitive(Station.D_TAG))))
        since?.let { put("since", JsonPrimitive(it)) }
    })
}
