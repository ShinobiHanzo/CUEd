package dev.cued.app.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import dev.cued.app.Graph
import dev.cued.app.analysis.AnalysisQueue.Companion.toTempo
import dev.cued.app.data.PlaybackSettings
import dev.cued.core.mix.CrossfadeCurve
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Owns the single [CrossfadePlayer] for the process. The service wraps it in
 * a MediaSession; the UI reads its spectrum and transition info directly.
 */
@UnstableApi
class PlayerHolder(private val context: Context, private val graph: Graph) {
    val spectrumBus = SpectrumBus(bands = 64)

    @Volatile
    private var latestSettings = PlaybackSettings(6_000L, CrossfadeCurve.EQUAL_POWER, true, 8f, 0.25f)

    init {
        graph.settings.playback.onEach { latestSettings = it }.launchIn(graph.appScope)
    }

    val player: CrossfadePlayer by lazy {
        CrossfadePlayer(
            context = context,
            spectrumBus = spectrumBus,
            tempoProvider = TempoProvider { mediaId, urgent ->
                val id = mediaId.toLongOrNull() ?: return@TempoProvider null
                val t = if (urgent) graph.analysis.ensureAnalysed(id) else graph.library.track(id)
                if (t?.analysedAt == null) graph.analysis.request(id, urgent = false)
                t?.toTempo()
            },
            settings = { latestSettings },
            onTrackFinished = { mediaId, fraction ->
                mediaId.toLongOrNull()?.let { id -> graph.appScope.launch { graph.library.recordPlayback(id, fraction) } }
            },
        )
    }
}
