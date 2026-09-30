package dev.cued.app.download.native

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HttpURLConnection transport for NewPipeExtractor. */
class NewPipeHttp : Downloader() {
    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.requestMethod = request.httpMethod()
        conn.instanceFollowRedirects = true
        var hasUa = false
        for ((k, vs) in request.headers()) {
            if (k.equals("User-Agent", true)) hasUa = true
            conn.setRequestProperty(k, vs.joinToString(", "))
        }
        if (!hasUa) conn.setRequestProperty("User-Agent", USER_AGENT)
        request.dataToSend()?.let { body ->
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val headers = conn.headerFields.filterKeys { it != null }.mapKeys { it.key }
        return Response(code, conn.responseMessage ?: "", headers, body, conn.url.toString())
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
