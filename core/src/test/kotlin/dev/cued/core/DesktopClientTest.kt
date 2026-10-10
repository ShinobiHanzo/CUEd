package dev.cued.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.cued.core.desktop.Assertion
import dev.cued.core.desktop.DesktopClient
import dev.cued.core.desktop.DesktopException
import dev.cued.core.desktop.ManifestEntry
import dev.cued.core.desktop.PairLink
import dev.cued.core.station.Nostr
import dev.cued.core.station.StationTrack
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [DesktopClient] against a tiny stand-in for cuedd that speaks protocol.md §2–§3 and §7–§11. */
class DesktopClientTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val secret = "0f".repeat(16)
    private val stored = HashMap<String, ByteArray>()      // sha → bytes received so far
    private val complete = HashSet<String>()
    private var lastMeta: String? = null
    private var lastAuth: String? = null
    private var lastAssertion: String? = null
    private var lastRange: String? = null

    private fun HttpExchange.reply(code: Int, body: String = "", type: String = "application/json", headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        responseHeaders.add("Content-Type", type)
        val bytes = body.toByteArray()
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
        close()
    }

    @BeforeTest fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            lastAuth = ex.requestHeaders.getFirst("Authorization")
            lastAssertion = ex.requestHeaders.getFirst("X-Cued-Assertion")
            val path = ex.requestURI.path
            val body = ex.requestBody.readBytes()
            when {
                ex.requestMethod == "POST" && path == "/api/pair" -> {
                    val o = Nostr.json.parseToJsonElement(String(body)).jsonObject
                    val ok = PairLink.proof(secret, o["device"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["nonce"]!!.jsonPrimitive.content) == o["proof"]!!.jsonPrimitive.content
                    if (!ok) ex.reply(403, """{"error":"wrong or expired pairing secret"}""")
                    else ex.reply(200, """{"token":"tok-${o["account"]!!.jsonPrimitive.content}-${o["biokey"]!!.jsonPrimitive.content.length}","desktop":{"name":"Fake","pubkey":"${"ab".repeat(32)}","relay":"ws://x/relay","funnel":null,"cert":null}}""")
                }
                path == "/api/auth/challenge" -> ex.reply(200, """{"challenge":"c0ffee","expiresInSec":120}""")
                ex.requestMethod == "POST" && path == "/api/sync/manifest" -> {
                    val tracks = Nostr.json.parseToJsonElement(String(body)).jsonObject["tracks"]!!.jsonArray
                    val missing = tracks.map { it.jsonObject["sha256"]!!.jsonPrimitive.content }.filter { it !in complete }
                    ex.reply(200, """{"missing":[${missing.joinToString(",") { "\"$it\"" }}],"known":${tracks.size - missing.size}}""")
                }
                path.startsWith("/api/sync/track/") -> {
                    val sha = path.removePrefix("/api/sync/track/")
                    when (ex.requestMethod) {
                        "HEAD" -> if (sha in complete) ex.reply(204) else ex.reply(200, headers = mapOf("X-Cued-Have" to (stored[sha]?.size ?: 0).toString()))
                        "PUT" -> {
                            lastMeta = ex.requestHeaders.getFirst("X-Cued-Meta")
                            lastRange = ex.requestHeaders.getFirst("Content-Range")
                            val start = lastRange?.substringAfter("bytes ")?.substringBefore("-")?.toLong() ?: 0L
                            val total = lastRange?.substringAfter("/")?.toLong() ?: body.size.toLong()
                            val have = stored[sha] ?: ByteArray(0)
                            if (start != have.size.toLong()) { ex.reply(416, """{"error":"resume from X-Cued-Have"}""", headers = mapOf("X-Cued-Have" to have.size.toString())); return@createContext }
                            val now = have + body
                            stored[sha] = now
                            if (now.size.toLong() >= total) {
                                val got = MessageDigest.getInstance("SHA-256").digest(now).joinToString("") { "%02x".format(it) }
                                if (got != sha) ex.reply(422, """{"error":"hash mismatch"}""") else { complete += sha; ex.reply(201, """{"sha256":"$sha"}""") }
                            } else ex.reply(202, """{"have":${now.size}}""", headers = mapOf("X-Cued-Have" to now.size.toString()))
                        }
                        else -> ex.reply(405)
                    }
                }
                path == "/api/library" -> ex.reply(200, """{"tracks":[{"sha256":"${"cd".repeat(32)}","title":"Rain","artist":"Field","album":"","durationMs":8000,"size":10,"mime":"audio/flac","genres":[],"bpm":null,"link":null,"path":"x","addedAt":1,"source":"phone:1","devices":[]}],"total":1}""")
                path == "/api/settings/shared" -> ex.reply(200, if (ex.requestMethod == "PUT") """{"relayEnabled":true,"friendStreaming":true,"theme":{"accent":"#fff"},"stationName":"","height":4}""" else """{"relayEnabled":false,"friendStreaming":true,"theme":{"bg":"#0e0f13","accent":"#5ef2c6"},"stationName":"Idris","updatedAt":0,"height":3}""")
                ex.requestMethod == "POST" && path == "/api/friends" -> ex.reply(200, """{"pubkey":"${"ef".repeat(32)}","npub":"npub1x","name":"Ana","status":"pending_out","relays":[],"addedAt":1,"updatedAt":1}""")
                path == "/api/friends" -> ex.reply(200, """[]""")
                ex.requestMethod == "DELETE" && path.startsWith("/api/friends/") -> ex.reply(204)
                path == "/api/stations/listen" -> ex.reply(200, """{"listening":null,"status":"idle"}""")
                path == "/api/me" -> if (lastAuth == null) ex.reply(401, """{"error":"bearer token required"}""") else ex.reply(200, """{"device":{"id":"x"}}""")
                else -> ex.reply(404, """{"error":"not found"}""")
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest fun stop() { server.stop(0) }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun pairsWithProofAndNewFields() {
        val link = PairLink(name = "Fake", addresses = listOf(base), secret = secret)
        val c = DesktopClient(base)
        val req = link.request("Pixel 8", "0011223344556677", account = "join", biokey = "04" + "ab".repeat(64), devhash = "cd".repeat(32))
        val resp = c.pair(req)
        assertEquals("tok-join-130", resp["token"]!!.jsonPrimitive.content)
        val bad = req.copy(proof = "00".repeat(32))
        val e = assertFailsWith<DesktopException> { c.pair(bad) }
        assertEquals(403, e.code)
        assertTrue(e.message!!.contains("secret"))
    }

    @Test fun bearerAndAssertionTravel() {
        val c = DesktopClient(base, token = "t1")
        c.me()
        assertEquals("Bearer t1", lastAuth)
        assertNull(lastAssertion) // http is the LAN: no assertion is sent
        assertEquals("c0ffee", c.challenge())
        assertTrue(c.trackUrl("ab".repeat(32)).endsWith("/api/track/${"ab".repeat(32)}?token=t1"))
        assertEquals("cued-bio|dev|c0ffee", String(Assertion.message("dev", "c0ffee")))
        assertEquals("c0ffee.0102", Assertion.header("c0ffee", byteArrayOf(1, 2)))
        val remote = DesktopClient("https://example.invalid", token = "t1", certSha256 = "ab".repeat(32))
        remote.assertion = "c0ffee.0102"
        assertTrue(remote.remote)
        assertTrue(remote.trackUrl("x").endsWith("?token=t1&assertion=c0ffee.0102"))
        assertEquals(DesktopClient.CONNECT_TIMEOUT_MS, 4_000)
    }

    @Test fun manifestAndResumableUpload() {
        val c = DesktopClient(base, token = "t1")
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val h = sha(data)
        val meta = ManifestEntry(key = "7", title = "Rain", artist = "Field", size = data.size.toLong(), sha256 = h, mime = "audio/flac", modified = 5)
        val r = c.manifest(listOf(meta))
        assertEquals(listOf(h), r.missing)
        assertEquals(0, r.known)
        // The desktop already holds the first 100 000 bytes: the client resumes from there.
        stored[h] = data.copyOfRange(0, 100_000)
        var progressed = 0L
        val done = c.upload(meta, data.size.toLong(), open = { off -> ByteArrayInputStream(data, off.toInt(), data.size - off.toInt()) }, onProgress = { progressed = it })
        assertTrue(done)
        assertEquals("bytes 100000-299999/300000", lastRange)
        assertTrue(lastMeta!!.contains("\"sha256\":\"$h\""))
        assertEquals(data.size.toLong(), progressed)
        assertTrue(h in complete)
        assertNull(c.have(h))
        assertEquals(1, c.manifest(listOf(meta)).known)
        // A second upload of a complete file is a no-op.
        assertTrue(c.upload(meta, data.size.toLong(), open = { error("must not read") }))
        // A hash that does not match what arrives is refused with 422.
        val bad = meta.copy(sha256 = "00".repeat(32))
        val e = assertFailsWith<DesktopException> { c.upload(bad, data.size.toLong(), open = { off -> ByteArrayInputStream(data, off.toInt(), data.size - off.toInt()) }) }
        assertEquals(422, e.code)
    }

    @Test fun libraryFriendsSharedAndStationTrackFields() {
        val c = DesktopClient(base, token = "t1")
        val lib = c.library("rain")
        assertEquals(1, lib["total"]!!.jsonPrimitive.content.toInt())
        val shared = c.shared()
        assertEquals(false, shared.relayEnabled)
        assertEquals("Idris", shared.stationName)
        assertEquals("#5ef2c6", shared.theme["accent"])
        assertEquals(true, c.putShared("""{"relayEnabled":true}""").relayEnabled)
        assertEquals("pending_out", c.addFriend("npub1x")["status"]!!.jsonPrimitive.content)
        assertEquals(0, c.friends().size)
        c.removeFriend("ef".repeat(32))
        assertEquals("idle", c.listenStatus()["status"]!!.jsonPrimitive.content)
        // The desktop adds stream/lyrics capability URLs to station entries; older states without them still decode.
        val withStream = Nostr.json.decodeFromString(StationTrack.serializer(), """{"title":"A","artist":"B","stream":"https://h/share/x/c","lyrics":"https://h/share/x/c/lyrics"}""")
        assertEquals("https://h/share/x/c", withStream.stream)
        assertNull(Nostr.json.decodeFromString(StationTrack.serializer(), """{"title":"A","artist":"B"}""").stream)
        assertEquals("""{"title":"A","artist":"B","album":"","durationMs":0,"link":null,"cover":null,"key":"","stream":null,"lyrics":null}""", Nostr.json.encodeToString(StationTrack.serializer(), StationTrack("A", "B")))
        assertEquals("04" + "ab".repeat(64), Assertion.sec1FromSpki(ByteArray(26) { 0x30 } + byteArrayOf(0x04) + ByteArray(64) { 0xab.toByte() }))
        assertNull(Assertion.sec1FromSpki(ByteArray(10)))
    }
}
