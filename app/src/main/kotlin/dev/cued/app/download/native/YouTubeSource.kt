package dev.cued.app.download.native

import dev.cued.core.download.Matcher
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube Music search and audio download through NewPipeExtractor, the
 * same library the NewPipe app uses. No API key. Picks the best AAC (m4a)
 * stream so no transcoding is needed.
 */
class YouTubeSource {
    init { ensureInit() }

    data class Found(val url: String, val title: String, val uploader: String, val durationSec: Int?, val thumbnailUrl: String?)

    fun search(query: String, limit: Int = 8): List<Matcher.Candidate> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        extractor.fetchPage()
        return extractor.initialPage.items.filterIsInstance<StreamInfoItem>().take(limit).map {
            Matcher.Candidate(it.url, it.name, it.uploaderName ?: "", it.duration.toInt().takeIf { d -> d > 0 })
        }
    }

    fun info(url: String): Pair<StreamInfo, AudioStream> {
        val info = try { StreamInfo.getInfo(ServiceList.YouTube, url) } catch (e: org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException) {
            val m = e.message.orEmpty()
            if (m.contains("not a bot", true) || m.contains("Sign in", true) || m.contains("reloaded", true))
                throw IllegalStateException("YouTube is challenging this network (\"$m\"). This usually clears on another network (mobile data vs Wi-Fi) or after updating CUEd, whose YouTube client follows the NewPipe project's.", e)
            throw e
        }
        val audio = info.audioStreams
            .filter { it.isUrl && it.content.isNotBlank() }
            .sortedWith(compareByDescending<AudioStream> { it.format?.name == "M4A" }.thenByDescending { it.averageBitrate })
            .firstOrNull() ?: error("No downloadable audio stream for $url")
        return info to audio
    }

    fun found(url: String): Found {
        val (info, _) = info(url)
        return Found(url, info.name, info.uploaderName ?: "", info.duration.toInt().takeIf { it > 0 }, info.thumbnails.maxByOrNull { it.width }?.url)
    }

    /** Extension for the chosen stream's container. */
    fun extensionOf(stream: AudioStream): String = when (stream.format?.name) { "M4A" -> "m4a"; "WEBMA", "WEBMA_OPUS" -> "webm"; "OPUS" -> "opus"; "MP3" -> "mp3"; else -> "m4a" }
    fun mimeOf(stream: AudioStream): String = when (extensionOf(stream)) { "m4a" -> "audio/mp4"; "webm" -> "audio/webm"; "opus" -> "audio/ogg"; "mp3" -> "audio/mpeg"; else -> "audio/mp4" }

    /** Downloads in ranged chunks (YouTube throttles single long reads). */
    fun download(stream: AudioStream, dest: File, onProgress: (Float) -> Unit) {
        val url = stream.content
        var total = -1L
        RandomAccessFile(dest, "rw").use { out ->
            out.setLength(0)
            var offset = 0L
            while (true) {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", NewPipeHttp.USER_AGENT)
                conn.setRequestProperty("Range", "bytes=$offset-${offset + CHUNK - 1}")
                try {
                    val code = conn.responseCode
                    if (code == 416) break
                    check(code == 206 || code == 200) { "HTTP $code from YouTube" }
                    if (total < 0) {
                        total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: conn.contentLengthLong.takeIf { code == 200 } ?: -1L
                    }
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    conn.inputStream.use { inp ->
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            if (total > 0) onProgress(((offset + got).toFloat() / total).coerceIn(0f, 0.95f))
                        }
                    }
                    offset += got
                    if (code == 200 || got == 0L || (total > 0 && offset >= total)) break
                } finally { conn.disconnect() }
            }
        }
        check(dest.length() > 100_000) { "Download too small (${dest.length()} bytes); YouTube may have changed something. Try again later or update CUEd." }
    }

    companion object {
        private const val CHUNK = 8L * 1024 * 1024
        @Volatile private var inited = false
        @Synchronized fun ensureInit() {
            if (!inited) { NewPipe.init(NewPipeHttp(), Localization("en", "US"), ContentCountry("US")); inited = true }
        }
        fun isYouTube(url: String) = url.contains("youtube.com/") || url.contains("youtu.be/")
    }
}
