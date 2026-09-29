package dev.cued.app.share.nfc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The payload the HCE service hands out when another phone taps this one. Set by the Share screen. */
object SharePayloadHolder {
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current
    fun set(payload: String?) { _current.value = payload }
    val bytes: ByteArray? get() = _current.value?.toByteArray(Charsets.UTF_8)
}
