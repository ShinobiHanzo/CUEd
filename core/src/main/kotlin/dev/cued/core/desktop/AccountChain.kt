package dev.cued.core.desktop

import dev.cued.core.crypto.Secp256k1
import dev.cued.core.crypto.hexToBytes
import dev.cued.core.crypto.toHex
import dev.cued.core.station.Nostr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The account chain (CUEd-desktop `docs/protocol.md` §8): a signed,
 * hash-linked list of blocks only the account key can extend. Every device
 * of the account holds a full copy; copies merge by "longer valid chain wins".
 * Mirrors `cued-proto/src/account.rs`; the canonical form hashed into
 * [Block.hash] is `[v, height, prev, ts, type, data, pubkey]` with no
 * whitespace and `data`'s keys in their original order.
 */
@Serializable
data class Block(
    val v: Int,
    val height: Long,
    val prev: String,
    val ts: Long,
    val type: String,
    val data: JsonObject,
    val pubkey: String,
    val hash: String,
    val sig: String,
) {
    fun verify(): Boolean = v == 1 && hash == AccountChain.hash(v, height, prev, ts, type, data, pubkey) &&
        runCatching { Secp256k1.verify(hash.hexToBytes(), pubkey.hexToBytes(), sig.hexToBytes()) }.getOrDefault(false)
}

@Serializable
data class AccountChain(val blocks: List<Block> = emptyList()) {
    val pubkey: String? get() = blocks.firstOrNull()?.pubkey
    val height: Long get() = blocks.size.toLong()
    val tipHash: String get() = blocks.lastOrNull()?.hash.orEmpty()

    /** Signs a new block with [secretHex] (must be the account key) and returns the extended chain. */
    fun append(secretHex: String, type: String, data: JsonObject, ts: Long): AccountChain {
        val sk = secretHex.hexToBytes()
        val pub = Secp256k1.publicKey(sk).toHex()
        require(pubkey == null || pubkey == pub) { "only the account key can extend its chain" }
        val h = hash(1, height, tipHash, ts, type, data, pub)
        val sig = Secp256k1.sign(h.hexToBytes(), sk).toHex()
        return AccountChain(blocks + Block(1, height, tipHash, ts, type, data, pub, h, sig))
    }

    /** Null when valid, else what is wrong. */
    fun validate(): String? {
        val first = blocks.firstOrNull() ?: return "empty"
        if (first.type != "create" || first.prev.isNotEmpty() || first.height != 0L) return "block 0 must be create"
        var prev = ""
        blocks.forEachIndexed { i, b ->
            if (b.height != i.toLong() || b.prev != prev || b.pubkey != first.pubkey || !b.verify()) return "block $i is not valid"
            prev = b.hash
        }
        return null
    }

    /** The chain to keep after seeing [other]: the longer valid one (equal height: lower tip hash). */
    fun merge(other: AccountChain): AccountChain {
        require(other.validate() == null) { "other chain is not valid" }
        if (blocks.isEmpty()) return other
        require(pubkey == other.pubkey) { "different account" }
        val takeOther = other.height > height || (other.height == height && other.tipHash < tipHash)
        return if (takeOther) other else this
    }

    fun latest(type: String): JsonObject? = blocks.lastOrNull { it.type == type }?.data

    fun name(): String = (latest("profile") ?: latest("create"))?.get("name")?.jsonPrimitive?.content.orEmpty()

    /** Every `settings` block merged in order; later keys win. */
    fun settings(): JsonObject {
        val acc = LinkedHashMap<String, JsonElement>()
        for (b in blocks) if (b.type == "settings") acc.putAll(b.data)
        return JsonObject(acc)
    }

    /** Devices currently bound: `bind_device` minus later `unbind_device`. */
    fun devices(): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        for (b in blocks) {
            val dh = b.data["devhash"]?.jsonPrimitive?.content ?: continue
            when (b.type) {
                "bind_device" -> { out.removeAll { it["devhash"]?.jsonPrimitive?.content == dh }; out += b.data }
                "unbind_device" -> out.removeAll { it["devhash"]?.jsonPrimitive?.content == dh }
            }
        }
        return out
    }

    fun encode(): String = Nostr.json.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String): AccountChain? = runCatching { Nostr.json.decodeFromString(serializer(), text) }.getOrNull()

        fun create(secretHex: String, name: String, ts: Long): AccountChain =
            AccountChain().append(secretHex, "create", JsonObject(mapOf("name" to JsonPrimitive(name))), ts)

        fun hash(v: Int, height: Long, prev: String, ts: Long, type: String, data: JsonObject, pubkey: String): String {
            val canonical = Nostr.json.encodeToString(JsonArray.serializer(), buildJsonArray {
                add(JsonPrimitive(v)); add(JsonPrimitive(height)); add(JsonPrimitive(prev)); add(JsonPrimitive(ts)); add(JsonPrimitive(type)); add(data); add(JsonPrimitive(pubkey))
            })
            return Secp256k1.sha256(canonical.toByteArray(Charsets.UTF_8)).toHex()
        }

        /** `sha256("cued-device|" + device id + "|" + salt)`: a unique hash of the device that reveals nothing about it. */
        fun devhash(deviceId: String, salt: String): String = Secp256k1.sha256("cued-device|$deviceId|$salt".toByteArray(Charsets.UTF_8)).toHex()

        fun accountId(pubkeyHex: String): String = Secp256k1.sha256(pubkeyHex.hexToBytes()).toHex()

        /** Reads `data` of a block the desktop sent as JSON, keeping key order. */
        fun data(json: String): JsonObject = Nostr.json.parseToJsonElement(json).jsonObject
    }
}
