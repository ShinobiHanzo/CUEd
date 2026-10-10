package dev.cued.core.desktop

import kotlinx.serialization.Serializable

/**
 * One line of the manifest a phone sends the desktop (CUEd-desktop
 * `docs/protocol.md` §3): what the phone has, addressed by the SHA-256 of
 * the file bytes. Field names are the wire names.
 */
@Serializable
data class ManifestEntry(
    val key: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val size: Long = 0,
    val sha256: String,
    val mime: String = "audio/mpeg",
    val genres: List<String> = emptyList(),
    val bpm: Float? = null,
    val link: String? = null,
    val modified: Long = 0,
)

/** What the desktop answered to a manifest: which hashes it lacks. */
data class ManifestResult(val missing: List<String>, val known: Int)

/** Shared settings from the account chain (§11), with the defaults the desktop uses. */
data class SharedSettings(
    val relayEnabled: Boolean = false,
    val friendStreaming: Boolean = true,
    val theme: Map<String, String> = emptyMap(),
    val stationName: String = "",
    val height: Long = 0,
)
