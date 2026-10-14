package dev.cued.core.party

import dev.cued.core.crypto.Secp256k1
import dev.cued.core.crypto.hexToBytes
import dev.cued.core.crypto.toHex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

/**
 * The shared queue ("party") protocol: a guest on the same Wi-Fi or within
 * Bluetooth range asks the host to search its library, queue a song or
 * nudge playback. Every message, request and reply alike, is a [PartyEnvelope]
 * signed with the sender's station key (BIP-340 over a tagged hash), so the
 * host only serves people in its friends list and the guest knows the host
 * really is the friend it claims to be. No account, no server, no third party.
 *
 * The same JSON travels over HTTP (`POST /party` on a phone, `POST /api/party`
 * on the desktop) and over Bluetooth LE (chunked through [Chunker]).
 */
@Serializable
data class PartyEnvelope(
    val v: Int = 1,
    /** Sender's station public key, x-only hex. */
    val pubkey: String,
    /** Unix seconds when signed; the receiver allows ±[Party.MAX_SKEW_SEC]. */
    val ts: Long,
    /** 8 random bytes, hex; a reply echoes the request's nonce. */
    val nonce: String,
    /** One of [Party.OPS]. */
    val op: String,
    /** Request arguments or reply payload; any JSON. */
    val arg: JsonElement = JsonNull,
    /** `false` on an error reply; [arg] then holds `{"error": "..."}`. */
    val ok: Boolean = true,
    val sig: String = "",
)

@Serializable
data class PartyTrack(val sha256: String = "", val id: Long = 0, val title: String, val artist: String = "", val album: String = "", val durationMs: Long = 0)

@Serializable
data class PartyNow(
    val current: PartyTrack? = null,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val queue: List<PartyTrack> = emptyList(),
    val queueLength: Int = 0,
)

@Serializable
data class PartyHello(
    val name: String,
    val pubkey: String,
    /** Whether guests may skip / pause / resume, not only queue. */
    val controls: Boolean,
    val now: PartyNow = PartyNow(),
)

/** A host found by the UDP beacon or a BLE scan. */
data class PartyBeacon(val name: String, val pubkey: String, val url: String?, val transport: String)

object Party {
    const val OP_HELLO = "hello"
    const val OP_SEARCH = "search"
    const val OP_QUEUE = "queue"
    const val OP_NOW = "now"
    const val OP_SKIP = "skip"
    const val OP_PAUSE = "pause"
    const val OP_PLAY = "play"
    val OPS = setOf(OP_HELLO, OP_SEARCH, OP_QUEUE, OP_NOW, OP_SKIP, OP_PAUSE, OP_PLAY)
    /** Ops a host only honours when its "guests may control playback" switch is on. */
    val CONTROL_OPS = setOf(OP_SKIP, OP_PAUSE, OP_PLAY)

    const val MAX_SKEW_SEC = 90L
    /** The UDP beacon port and the question/answer on it. */
    const val BEACON_PORT = 8772
    const val BEACON_QUERY = "CUED-PARTY?"
    const val BEACON_PREFIX = "CUED-PARTY "
    /** BLE GATT service and characteristics (host writes replies to TX by notification, guest writes requests to RX). */
    const val BLE_SERVICE = "7a2e0c0e-9a6b-4c57-8c3f-00c0ed0ac0d1"
    const val BLE_RX = "7a2e0c0e-9a6b-4c57-8c3f-00c0ed0ac0d2"
    const val BLE_TX = "7a2e0c0e-9a6b-4c57-8c3f-00c0ed0ac0d3"
    const val MAX_RESULTS = 12
    const val MAX_QUERY = 80

    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun nonce(random: SecureRandom = SecureRandom()): String = ByteArray(8).also(random::nextBytes).toHex()

    /** Canonical JSON: object keys sorted, no whitespace, so both sides hash the same bytes. */
    fun canonical(e: JsonElement): String = when (e) {
        is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, v) -> json.encodeToString(JsonPrimitive(k)) + ":" + canonical(v) }
        is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> if (e.isString) json.encodeToString(JsonPrimitive(e.content)) else e.content
        JsonNull -> "null"
    }

    /** What gets signed: a tagged hash over every field but the signature. */
    fun digest(e: PartyEnvelope): ByteArray {
        val body = "cued-party|${e.v}|${e.pubkey}|${e.ts}|${e.nonce}|${e.op}|${if (e.ok) 1 else 0}|${canonical(e.arg)}"
        return Secp256k1.taggedHash("CUEd/party", body.toByteArray(Charsets.UTF_8))
    }

    fun sign(secretHex: String, op: String, arg: JsonElement = JsonNull, ok: Boolean = true, nonce: String = nonce(), ts: Long = System.currentTimeMillis() / 1000): PartyEnvelope {
        val sk = secretHex.hexToBytes()
        val pub = Secp256k1.publicKey(sk).toHex()
        val unsigned = PartyEnvelope(pubkey = pub, ts = ts, nonce = nonce, op = op, arg = arg, ok = ok)
        return unsigned.copy(sig = Secp256k1.sign(digest(unsigned), sk).toHex())
    }

    /** Signature and shape only; freshness and friendship are the caller's ([check]). */
    fun verify(e: PartyEnvelope): Boolean = runCatching {
        e.v == 1 && e.op in OPS && e.nonce.length == 16 && e.pubkey.length == 64 &&
            Secp256k1.verify(digest(e.copy(sig = "")), e.pubkey.hexToBytes(), e.sig.hexToBytes())
    }.getOrDefault(false)

    sealed class Check {
        object Ok : Check()
        data class Bad(val reason: String) : Check()
    }

    /**
     * The host's gate: signature, clock skew, replay (nonce seen before), and
     * `pubkey` being an accepted friend. [friendStatus] returns the stored
     * status for a pubkey or null when unknown.
     */
    fun check(e: PartyEnvelope, nowSec: Long, seenNonce: (String) -> Boolean, friendStatus: (String) -> String?): Check {
        if (!verify(e)) return Check.Bad("bad signature")
        if (kotlin.math.abs(nowSec - e.ts) > MAX_SKEW_SEC) return Check.Bad("clock is off by more than ${MAX_SKEW_SEC}s")
        if (seenNonce(e.nonce)) return Check.Bad("replayed request")
        return when (friendStatus(e.pubkey)) {
            "friends" -> Check.Ok
            null -> Check.Bad("not a friend of this host")
            else -> Check.Bad("friend request still pending")
        }
    }

    fun encode(e: PartyEnvelope): String = json.encodeToString(e)
    fun decode(text: String): PartyEnvelope? = runCatching { json.decodeFromString<PartyEnvelope>(text) }.getOrNull()

    fun errorArg(message: String): JsonElement = buildJsonObject { put("error", JsonPrimitive(message)) }
    fun errorOf(e: PartyEnvelope): String? = if (e.ok) null else (e.arg as? JsonObject)?.get("error")?.jsonPrimitive?.content ?: "request failed"

    fun searchArg(q: String): JsonElement = buildJsonObject { put("q", JsonPrimitive(q.take(MAX_QUERY))) }
    fun queueArg(t: PartyTrack): JsonElement = buildJsonObject {
        if (t.sha256.isNotEmpty()) put("sha256", JsonPrimitive(t.sha256))
        if (t.id != 0L) put("id", JsonPrimitive(t.id))
        put("title", JsonPrimitive(t.title)); put("artist", JsonPrimitive(t.artist))
    }
    fun queryOf(arg: JsonElement): String = (arg as? JsonObject)?.get("q")?.jsonPrimitive?.content?.take(MAX_QUERY) ?: ""
    fun trackOf(arg: JsonElement): PartyTrack? = runCatching { json.decodeFromJsonElement(PartyTrack.serializer(), arg) }.getOrNull()
    fun tracksOf(arg: JsonElement): List<PartyTrack> = runCatching { (arg as JsonArray).map { json.decodeFromJsonElement(PartyTrack.serializer(), it) } }.getOrDefault(emptyList())
    fun helloOf(arg: JsonElement): PartyHello? = runCatching { json.decodeFromJsonElement(PartyHello.serializer(), arg) }.getOrNull()
    fun nowOf(arg: JsonElement): PartyNow? = runCatching { json.decodeFromJsonElement(PartyNow.serializer(), arg) }.getOrNull()
    fun toJson(hello: PartyHello): JsonElement = json.encodeToJsonElement(PartyHello.serializer(), hello)
    fun toJson(now: PartyNow): JsonElement = json.encodeToJsonElement(PartyNow.serializer(), now)
    fun toJson(tracks: List<PartyTrack>): JsonElement = JsonArray(tracks.map { json.encodeToJsonElement(PartyTrack.serializer(), it) })

    /** Beacon answer: `CUED-PARTY {"name":..,"pubkey":..,"url":"http://ip:port"}`. */
    fun beaconAnswer(name: String, pubkey: String, url: String): String =
        BEACON_PREFIX + json.encodeToString(buildJsonObject { put("name", JsonPrimitive(name)); put("pubkey", JsonPrimitive(pubkey)); put("url", JsonPrimitive(url)) })

    fun parseBeacon(text: String): PartyBeacon? = runCatching {
        if (!text.startsWith(BEACON_PREFIX)) return null
        val o = json.parseToJsonElement(text.removePrefix(BEACON_PREFIX)).jsonObject
        val pub = o["pubkey"]!!.jsonPrimitive.content
        if (pub.length != 64) return null
        PartyBeacon(o["name"]?.jsonPrimitive?.content ?: "", pub, o["url"]?.jsonPrimitive?.content, "lan")
    }.getOrNull()

    /** Remembers nonces for [ttlSec] so a captured request cannot be replayed. */
    class NonceCache(private val ttlSec: Long = MAX_SKEW_SEC * 2) {
        private val seen = HashMap<String, Long>()
        @Synchronized fun seenBefore(nonce: String, nowSec: Long): Boolean {
            seen.entries.removeAll { nowSec - it.value > ttlSec }
            return seen.put(nonce, nowSec) != null
        }
    }
}

/**
 * Splits a message into BLE-sized frames and puts them back together:
 * `[index:1][total:1][payload]`. A message is at most 255 frames; with a
 * 20-byte floor that is still 4.5 KB, more than a signed envelope ever is.
 */
object Chunker {
    const val HEADER = 2

    fun split(message: ByteArray, mtuPayload: Int): List<ByteArray> {
        val size = (mtuPayload - HEADER).coerceAtLeast(1)
        val total = ((message.size + size - 1) / size).coerceAtLeast(1)
        require(total <= 255) { "message too long for BLE: ${message.size} bytes" }
        return (0 until total).map { i ->
            val from = i * size
            val to = minOf(message.size, from + size)
            byteArrayOf(i.toByte(), total.toByte()) + message.copyOfRange(from, to)
        }
    }

    /** Collects frames in any order; [feed] returns the whole message once the last piece lands. */
    class Assembler {
        private var parts: Array<ByteArray?> = emptyArray()
        private var got = 0

        fun feed(frame: ByteArray): ByteArray? {
            if (frame.size < HEADER) return null
            val index = frame[0].toInt() and 0xff
            val total = frame[1].toInt() and 0xff
            if (total == 0 || index >= total) return null
            if (parts.size != total) { parts = arrayOfNulls(total); got = 0 }
            if (parts[index] == null) got++
            parts[index] = frame.copyOfRange(HEADER, frame.size)
            if (got < total) return null
            val out = parts.fold(ByteArray(0)) { acc, p -> acc + p!! }
            parts = emptyArray(); got = 0
            return out
        }

        fun reset() { parts = emptyArray(); got = 0 }
    }
}
