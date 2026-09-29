package dev.cued.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.cued.core.mix.CrossfadeCurve
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ScrubberMode { STANDARD, STATIC_SPECTROGRAM, REACTIVE_SPECTROGRAM }
enum class DownloadBackend { TERMUX, COMPANION }

/**
 * Two independent blend modes:
 *  - Standard crossfade: plain volume blend, on by default.
 *  - Tempo-match crossfade: beat-aligned, speed-matched blend; off by default and
 *    with its own duration. When it is on but the two tracks don't relate by a
 *    simple tempo ratio, playback falls back to the standard crossfade (if that is
 *    on) or a gapless cut.
 */
data class PlaybackSettings(
    val crossfadeEnabled: Boolean,
    val crossfadeMs: Long,
    val curve: CrossfadeCurve,
    val tempoMatch: Boolean,
    val tempoMatchMs: Long,
    val maxStretchPercent: Float,
    val minBpmConfidence: Float,
    /** Cut quiet stretches (intros, outros, hidden-track gaps) while playing. */
    val skipSilence: Boolean,
    /** Peak level in dBFS below which audio counts as silent. */
    val silenceThresholdDb: Float,
    /** Silence shorter than this is kept; only the excess is skipped. */
    val silenceToleranceMs: Int,
) {
    companion object {
        val DEFAULT = PlaybackSettings(
            crossfadeEnabled = true, crossfadeMs = 6_000L, curve = CrossfadeCurve.EQUAL_POWER,
            tempoMatch = false, tempoMatchMs = 8_000L, maxStretchPercent = 8f, minBpmConfidence = 0.25f,
            skipSilence = false, silenceThresholdDb = -50f, silenceToleranceMs = 700,
        )
    }
}

data class CarSettings(
    /** Manual toggle from the sidebar. */
    val carMode: Boolean,
    /** Enter car mode by itself when Android reports the car UI mode (dock, Android Auto on older versions). */
    val autoCarMode: Boolean,
)

data class UiSettings(
    val scrubberMode: ScrubberMode,
    val visualDelayMs: Int,
    val spectrumBands: Int,
)

data class DownloadSettings(
    val backend: DownloadBackend,
    val companionUrl: String,
    val format: String,
)

/** Everything user-tunable, persisted with DataStore. Defaults are the values a DJ-ish listener would expect. */
class Settings(private val context: Context) {
    private object K {
        val crossfadeEnabled = booleanPreferencesKey("crossfade_enabled")
        val crossfadeMs = longPreferencesKey("crossfade_ms")
        val tempoMatchMs = longPreferencesKey("tempo_match_ms")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val silenceThresholdDb = floatPreferencesKey("silence_threshold_db")
        val silenceToleranceMs = intPreferencesKey("silence_tolerance_ms")
        val carMode = booleanPreferencesKey("car_mode")
        val autoCarMode = booleanPreferencesKey("auto_car_mode")
        val curve = stringPreferencesKey("crossfade_curve")
        val tempoMatch = booleanPreferencesKey("tempo_match")
        val maxStretch = floatPreferencesKey("max_stretch_percent")
        val minConfidence = floatPreferencesKey("min_bpm_confidence")
        val scrubber = stringPreferencesKey("scrubber_mode")
        val visualDelay = intPreferencesKey("visual_delay_ms")
        val bands = intPreferencesKey("spectrum_bands")
        val backend = stringPreferencesKey("download_backend")
        val companionUrl = stringPreferencesKey("companion_url")
        val format = stringPreferencesKey("download_format")
        val sharePort = intPreferencesKey("share_port")
        val lastScanAt = longPreferencesKey("last_scan_at")
    }

    val playback: Flow<PlaybackSettings> = context.dataStore.data.map { p ->
        PlaybackSettings(
            crossfadeEnabled = p[K.crossfadeEnabled] ?: PlaybackSettings.DEFAULT.crossfadeEnabled,
            crossfadeMs = p[K.crossfadeMs] ?: PlaybackSettings.DEFAULT.crossfadeMs,
            curve = p[K.curve]?.let { runCatching { CrossfadeCurve.valueOf(it) }.getOrNull() } ?: PlaybackSettings.DEFAULT.curve,
            tempoMatch = p[K.tempoMatch] ?: PlaybackSettings.DEFAULT.tempoMatch,
            tempoMatchMs = p[K.tempoMatchMs] ?: PlaybackSettings.DEFAULT.tempoMatchMs,
            maxStretchPercent = p[K.maxStretch] ?: PlaybackSettings.DEFAULT.maxStretchPercent,
            minBpmConfidence = p[K.minConfidence] ?: PlaybackSettings.DEFAULT.minBpmConfidence,
            skipSilence = p[K.skipSilence] ?: PlaybackSettings.DEFAULT.skipSilence,
            silenceThresholdDb = p[K.silenceThresholdDb] ?: PlaybackSettings.DEFAULT.silenceThresholdDb,
            silenceToleranceMs = p[K.silenceToleranceMs] ?: PlaybackSettings.DEFAULT.silenceToleranceMs,
        )
    }

    val car: Flow<CarSettings> = context.dataStore.data.map { p ->
        CarSettings(carMode = p[K.carMode] ?: false, autoCarMode = p[K.autoCarMode] ?: true)
    }

    val ui: Flow<UiSettings> = context.dataStore.data.map { p ->
        UiSettings(
            scrubberMode = p[K.scrubber]?.let { runCatching { ScrubberMode.valueOf(it) }.getOrNull() } ?: ScrubberMode.REACTIVE_SPECTROGRAM,
            visualDelayMs = p[K.visualDelay] ?: 120,
            spectrumBands = p[K.bands] ?: 48,
        )
    }

    val download: Flow<DownloadSettings> = context.dataStore.data.map { p ->
        DownloadSettings(
            backend = p[K.backend]?.let { runCatching { DownloadBackend.valueOf(it) }.getOrNull() } ?: DownloadBackend.TERMUX,
            companionUrl = p[K.companionUrl] ?: "http://192.168.1.10:8766",
            format = p[K.format] ?: "mp3",
        )
    }

    val sharePort: Flow<Int> = context.dataStore.data.map { it[K.sharePort] ?: 8765 }

    suspend fun playbackNow() = playback.first()
    suspend fun uiNow() = ui.first()
    suspend fun downloadNow() = download.first()

    suspend fun setCrossfadeEnabled(on: Boolean) = context.dataStore.edit { it[K.crossfadeEnabled] = on }
    suspend fun setCrossfadeMs(ms: Long) = context.dataStore.edit { it[K.crossfadeMs] = ms.coerceIn(1_000L, 20_000L) }
    suspend fun setTempoMatchMs(ms: Long) = context.dataStore.edit { it[K.tempoMatchMs] = ms.coerceIn(2_000L, 32_000L) }
    suspend fun setSkipSilence(on: Boolean) = context.dataStore.edit { it[K.skipSilence] = on }
    suspend fun setSilenceThresholdDb(db: Float) = context.dataStore.edit { it[K.silenceThresholdDb] = db.coerceIn(-80f, -20f) }
    suspend fun setSilenceToleranceMs(ms: Int) = context.dataStore.edit { it[K.silenceToleranceMs] = ms.coerceIn(100, 5_000) }
    suspend fun setCarMode(on: Boolean) = context.dataStore.edit { it[K.carMode] = on }
    suspend fun setAutoCarMode(on: Boolean) = context.dataStore.edit { it[K.autoCarMode] = on }
    suspend fun setCurve(curve: CrossfadeCurve) = context.dataStore.edit { it[K.curve] = curve.name }
    suspend fun setTempoMatch(on: Boolean) = context.dataStore.edit { it[K.tempoMatch] = on }
    suspend fun setMaxStretchPercent(v: Float) = context.dataStore.edit { it[K.maxStretch] = v.coerceIn(1f, 16f) }
    suspend fun setMinBpmConfidence(v: Float) = context.dataStore.edit { it[K.minConfidence] = v.coerceIn(0f, 1f) }
    suspend fun setScrubberMode(mode: ScrubberMode) = context.dataStore.edit { it[K.scrubber] = mode.name }
    suspend fun setVisualDelayMs(ms: Int) = context.dataStore.edit { it[K.visualDelay] = ms.coerceIn(0, 500) }
    suspend fun setSpectrumBands(n: Int) = context.dataStore.edit { it[K.bands] = n.coerceIn(16, 96) }
    suspend fun setDownloadBackend(b: DownloadBackend) = context.dataStore.edit { it[K.backend] = b.name }
    suspend fun setCompanionUrl(url: String) = context.dataStore.edit { it[K.companionUrl] = url.trim().trimEnd('/') }
    suspend fun setDownloadFormat(fmt: String) = context.dataStore.edit { it[K.format] = fmt }
    suspend fun setSharePort(port: Int) = context.dataStore.edit { it[K.sharePort] = port.coerceIn(1024, 65535) }
}
