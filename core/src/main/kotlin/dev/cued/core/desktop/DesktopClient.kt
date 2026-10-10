package dev.cued.core.desktop

import dev.cued.core.station.Nostr
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** The desktop answered with an error status. */
class DesktopException(val code: Int, message: String) : Exception(message)

/**
 * The phone's side of CUEd-desktop `docs/protocol.md`, on plain
 * `HttpURLConnection` so it is pure JVM and unit-tested against a local
 * server. One instance per desktop: [base] is a LAN `http://` address or the
 * funnel's `https://`; with a [certSha256] the TLS certificate is pinned to
 * that fingerprint and no CA is consulted.
 *
 * Writes only work on the LAN (§7). Away from home the desktop is read-only
 * and every call needs an [assertion] from the biometric key; `remote`
 * tells the caller which world it is in before it tries.
 */
class DesktopClient(val base: String, var token: String? = null, val certSha256: String? = null) {
    /** `<challenge>.<sig hex>` from [Assertion.header]; sent on every request while set. */
    @Volatile var assertion: String? = null

    /** True when this client goes through the funnel: reads only, assertion required. */
    val remote: Boolean get() = base.startsWith("https://", ignoreCase = true)

    class Response(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
        val ok: Boolean get() = code in 200..299
        fun json(): JsonElement = runCatching { Nostr.json.parseToJsonElement(text) }.getOrDefault(JsonNull)
        fun header(name: String): String? = headers[name.lowercase()]
    }

    // ---- pairing ----------------------------------------------------------------------------

    /** `POST /api/pair`; returns the body (token, desktop, optional sealed account). */
    fun pair(req: PairRequest): JsonObject {
        val r = call("POST", "/api/pair", req.toJson().toByteArray(Charsets.UTF_8), "application/json", auth = false)
        if (!r.ok) throw DesktopException(r.code, errorOf(r))
        return r.json().jsonObject
    }

    /** `GET /api/auth/challenge` → the nonce the biometric key signs. */
    fun challenge(): String {
        val r = call("GET", "/api/auth/challenge")
        if (!r.ok) throw DesktopException(r.code, errorOf(r))
        return r.json().jsonObject["challenge"]?.jsonPrimitive?.contentOrNull ?: throw DesktopException(r.code, "no challenge in the answer")
    }

    fun me(): JsonObject = getJson("/api/me")
    fun status(): JsonObject = getJson("/api/status")

    // ---- sync (LAN) -------------------------------------------------------------------------

    fun manifest(entries: List<ManifestEntry>): ManifestResult {
        val body = """{"tracks":${Nostr.json.encodeToString(ListSerializer(ManifestEntry.serializer()), entries)}}"""
        val o = postJson("/api/sync/manifest", body)
        return ManifestResult(
            missing = o["missing"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            known = o["known"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    /** Bytes the desktop already holds for this hash; null when the whole file is there. */
    fun have(sha256: String): Long? {
        val r = call("HEAD", "/api/sync/track/$sha256")
        return when (r.code) {
            204 -> null
            200 -> r.header("x-cued-have")?.toLongOrNull() ?: 0L
            else -> throw DesktopException(r.code, errorOf(r))
        }
    }

    /**
     * Uploads one file, resuming from what the desktop already has. [open]
     * gives a stream positioned at the requested offset. Returns true when the
     * desktop confirmed the complete file.
     */
    fun upload(meta: ManifestEntry, total: Long, open: (offset: Long) -> InputStream, onProgress: (sent: Long) -> Unit = {}): Boolean {
        val start = have(meta.sha256) ?: return true
        if (start > total) throw DesktopException(416, "desktop holds more than the file is long; it will be re-sent")
        val conn = connection("PUT", "/api/sync/track/${meta.sha256}")
        conn.setRequestProperty("Content-Type", meta.mime.ifBlank { "application/octet-stream" })
        conn.setRequestProperty("X-Cued-Meta", Nostr.json.encodeToString(ManifestEntry.serializer(), meta))
        if (start > 0) conn.setRequestProperty("Content-Range", "bytes $start-${total - 1}/$total")
        val len = total - start
        conn.setFixedLengthStreamingMode(len)
        conn.doOutput = true
        try {
            conn.outputStream.use { out ->
                open(start).use { src ->
                    val buf = ByteArray(1 shl 16)
                    var sent = 0L
                    var last = 0L
                    while (true) {
                        val n = src.read(buf); if (n < 0) break
                        out.write(buf, 0, n); sent += n
                        if (sent - last >= (1 shl 20)) { last = sent; onProgress(start + sent) }
                    }
                }
            }
            val r = read(conn)
            return when (r.code) {
                201 -> { onProgress(total); true }
                202 -> false
                else -> throw DesktopException(r.code, errorOf(r))
            }
        } finally { conn.disconnect() }
    }

    fun syncStatus(): JsonObject = getJson("/api/sync/status")
    fun snapshot(): JsonObject = postJson("/api/backup/snapshot", "{}")
    fun putBlob(name: String, bytes: ByteArray) { val r = call("PUT", "/api/backup/blob/$name", bytes, "application/octet-stream"); if (!r.ok) throw DesktopException(r.code, errorOf(r)) }
    fun getBlob(name: String): ByteArray { val r = call("GET", "/api/backup/blob/$name"); if (!r.ok) throw DesktopException(r.code, errorOf(r)); return r.body }

    // ---- library (read-only, remote allowed) ---------------------------------------------------

    fun library(query: String = "", limit: Int = 500, offset: Int = 0): JsonObject = getJson("/api/library?q=${enc(query)}&limit=$limit&offset=$offset")
    fun trackMeta(sha256: String): JsonObject = getJson("/api/track/$sha256/meta")
    fun lyrics(sha256: String): JsonObject? { val r = call("GET", "/api/track/$sha256/lyrics"); return if (r.ok) r.json().jsonObject else null }

    /**
     * A URL a media player can open directly: the token (and the assertion
     * away from home) travel as query parameters because players cannot set
     * headers.
     */
    fun trackUrl(sha256: String): String = base.trimEnd('/') + "/api/track/$sha256" + authQuery()
    fun coverUrl(sha256: String): String = base.trimEnd('/') + "/api/track/$sha256/cover" + authQuery()

    private fun authQuery(): String {
        val q = ArrayList<String>()
        token?.let { q += "token=" + enc(it) }
        if (remote) assertion?.let { q += "assertion=" + enc(it) }
        return if (q.isEmpty()) "" else "?" + q.joinToString("&")
    }

    // ---- account ------------------------------------------------------------------------------

    fun account(): JsonObject = getJson("/api/account")
    fun chain(): JsonObject = getJson("/api/account/chain")
    /** Sends our copy; the desktop keeps the longer valid chain. */
    fun mergeChain(chainJson: String): JsonObject = postJson("/api/account/chain", chainJson)
    /** `keep` at pairing: our key and chain, sealed under the pairing secret. */
    fun importAccount(sealed: String): JsonObject = postJson("/api/account/import", """{"sealed":${quote(sealed)}}""")

    // ---- shared settings ----------------------------------------------------------------------

    fun shared(): SharedSettings = parseShared(getJson("/api/settings/shared"))
    fun putShared(patchJson: String): SharedSettings = parseShared(putJson("/api/settings/shared", patchJson))

    // ---- friends ------------------------------------------------------------------------------

    fun friends(): JsonArray = getArray("/api/friends")
    fun friendRequests(): JsonArray = getArray("/api/friends/requests")
    fun friendLink(): JsonObject = getJson("/api/friends/link")
    fun addFriend(text: String): JsonObject = postJson("/api/friends", """{"text":${quote(text)}}""")
    fun acceptFriend(pubkey: String): JsonObject = postJson("/api/friends/$pubkey/accept", "{}")
    fun removeFriend(pubkey: String) { val r = call("DELETE", "/api/friends/$pubkey"); if (!r.ok && r.code != 404) throw DesktopException(r.code, errorOf(r)) }

    // ---- stations / listening -----------------------------------------------------------------

    fun stationsFeed(): JsonObject = getJson("/api/stations/feed")
    fun listen(pubkey: String): JsonObject = postJson("/api/stations/listen", """{"pubkey":${quote(pubkey)}}""")
    fun stopListening(): JsonObject { val r = call("DELETE", "/api/stations/listen"); if (!r.ok) throw DesktopException(r.code, errorOf(r)); return r.json().jsonObject }
    fun listenStatus(): JsonObject = getJson("/api/stations/listen")

    // ---- plumbing -----------------------------------------------------------------------------

    private fun getJson(path: String): JsonObject { val r = call("GET", path); if (!r.ok) throw DesktopException(r.code, errorOf(r)); return r.json().jsonObject }
    private fun getArray(path: String): JsonArray { val r = call("GET", path); if (!r.ok) throw DesktopException(r.code, errorOf(r)); return r.json().jsonArray }
    private fun postJson(path: String, body: String): JsonObject { val r = call("POST", path, body.toByteArray(Charsets.UTF_8), "application/json"); if (!r.ok) throw DesktopException(r.code, errorOf(r)); return r.json().jsonObject }
    private fun putJson(path: String, body: String): JsonObject { val r = call("PUT", path, body.toByteArray(Charsets.UTF_8), "application/json"); if (!r.ok) throw DesktopException(r.code, errorOf(r)); return r.json().jsonObject }

    private fun errorOf(r: Response): String = runCatching { r.json().jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: r.text.take(200).ifBlank { "HTTP ${r.code}" }

    fun call(method: String, path: String, body: ByteArray? = null, contentType: String? = null, auth: Boolean = true): Response {
        val conn = connection(method, path, auth)
        try {
            if (body != null) {
                conn.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                conn.setFixedLengthStreamingMode(body.size)
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            return read(conn)
        } finally { conn.disconnect() }
    }

    /**
     * Opens a GET on one of this desktop's URLs (a [trackUrl], [coverUrl] or
     * capability link) with the certificate pin and the assertion applied, for
     * callers that stream the body themselves. The caller disconnects it.
     */
    fun open(url: String): HttpURLConnection {
        require(url.startsWith(base.trimEnd('/'))) { "not a URL of this desktop" }
        val conn = URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS; conn.readTimeout = 120_000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (remote) assertion?.let { conn.setRequestProperty("X-Cued-Assertion", it) }
        if (conn is HttpsURLConnection && certSha256 != null) pin(conn, certSha256)
        return conn
    }

    private fun connection(method: String, path: String, auth: Boolean = true): HttpURLConnection {
        val conn = URL(base.trimEnd('/') + path).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS; conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = method
        conn.instanceFollowRedirects = false
        if (auth) token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (remote) assertion?.let { conn.setRequestProperty("X-Cued-Assertion", it) }
        if (conn is HttpsURLConnection && certSha256 != null) pin(conn, certSha256)
        return conn
    }

    private fun read(conn: HttpURLConnection): Response {
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        val headers = HashMap<String, String>()
        for ((k, v) in conn.headerFields) if (k != null && v.isNotEmpty()) headers[k.lowercase()] = v.last()
        return Response(code, headers, body)
    }

    /** No CA: the only thing trusted is the SHA-256 of the certificate the QR carried. */
    private fun pin(conn: HttpsURLConnection, fingerprint: String) {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val got = sha256Hex(chain[0].encoded)
                if (!got.equals(fingerprint.replace(":", ""), ignoreCase = true)) throw java.security.cert.CertificateException("certificate pin mismatch: desktop presented ${got.take(16)}…, QR said ${fingerprint.take(16)}…")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        conn.sslSocketFactory = ctx.socketFactory
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 60_000

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        fun parseShared(o: JsonObject): SharedSettings = SharedSettings(
            relayEnabled = o["relayEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            friendStreaming = o["friendStreaming"]?.jsonPrimitive?.booleanOrNull ?: true,
            theme = (o["theme"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap() ?: emptyMap(),
            stationName = o["stationName"]?.jsonPrimitive?.contentOrNull ?: "",
            height = o["height"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }
}
