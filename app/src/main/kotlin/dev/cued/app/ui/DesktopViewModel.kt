package dev.cued.app.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dev.cued.app.Graph
import dev.cued.app.data.Desktop
import dev.cued.app.data.Friend
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.desktop.DesktopService
import dev.cued.core.desktop.PairLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Desktops, friends and the desktop library, for the screens. */
@androidx.annotation.OptIn(UnstableApi::class)
class DesktopViewModel(private val graph: Graph) : ViewModel() {
    private val svc: DesktopService get() = graph.desktop

    val desktops: StateFlow<List<Desktop>> = svc.store.desktops.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val friends: StateFlow<List<Friend>> = svc.store.friends.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val progress: StateFlow<DesktopService.SyncProgress?> = svc.progress
    val phoneName: StateFlow<String> = svc.store.phoneName.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val sharedJson: StateFlow<String?> = svc.store.sharedJson.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A pairing code waiting for the account choice (set by a scan, a tap or a paste). */
    val pendingPair = MutableStateFlow<PairLink?>(null)
    /** One-line outcome of the last action, for a snackbar. */
    val message = MutableStateFlow<String?>(null)

    val biometricSupported: Boolean get() = svc.bio.supported

    fun offerPair(text: String): Boolean {
        val link = PairLink.decode(text) ?: return false
        pendingPair.value = link
        return true
    }

    fun pair(link: PairLink, account: String, activity: Activity?) {
        pendingPair.value = null
        viewModelScope.launch {
            message.value = svc.pair(link, account, activity).fold(
                { d -> when (account) { "join" -> "Paired with ${d.label}; this phone now uses its account"; "keep" -> "Paired with ${d.label}; it now uses this phone's account"; else -> "Paired with ${d.label}" } },
                { "Pairing failed: ${it.message}" },
            )
        }
    }

    fun sync(d: Desktop, activity: Activity? = null) { viewModelScope.launch { message.value = svc.sync(d, activity).fold({ "Backup done: $it" }, { "Backup failed: ${it.message}" }) } }
    fun unpair(d: Desktop) { viewModelScope.launch { svc.unpair(d); message.value = "Unlinked ${d.label}" } }
    fun setPhoneName(n: String) { viewModelScope.launch { svc.store.setPhoneName(n) } }
    fun setShared(d: Desktop, patchJson: String) { viewModelScope.launch { svc.putShared(d, patchJson).onFailure { message.value = it.message } } }

    // friends
    val myFriendLink = MutableStateFlow<String?>(null)
    fun loadFriendLink() { viewModelScope.launch { myFriendLink.value = runCatching { svc.myFriendLink().encode() }.getOrNull() } }
    fun addFriend(text: String) { viewModelScope.launch { message.value = svc.addFriend(text).fold({ if (it.status == "friends") "You and ${it.label} are friends" else "Request sent to ${it.label}" }, { it.message }) } }
    fun acceptFriend(f: Friend) { viewModelScope.launch { message.value = svc.acceptFriend(f.pubkey).fold({ "You and ${it.label} are friends" }, { it.message }) } }
    fun removeFriend(f: Friend) { viewModelScope.launch { svc.removeFriend(f.pubkey); message.value = "Removed ${f.label}" } }

    // the desktop library
    val remoteTracks = MutableStateFlow<List<DesktopService.DesktopTrack>>(emptyList())
    val remoteBusy = MutableStateFlow<String?>(null)
    fun loadLibrary(d: Desktop, query: String, activity: Activity?) {
        viewModelScope.launch {
            remoteBusy.value = "Loading…"
            svc.library(d, query, activity).fold({ remoteTracks.value = it; remoteBusy.value = null }, { remoteBusy.value = "Could not load: ${it.message}" })
        }
    }
    fun playRemote(d: Desktop, t: DesktopService.DesktopTrack, activity: Activity?, onReady: (TrackEntity) -> Unit) {
        viewModelScope.launch {
            remoteBusy.value = "Fetching ${t.title}…"
            svc.fetchForPlayback(d, t, activity).fold({ remoteBusy.value = null; onReady(it) }, { remoteBusy.value = "Could not fetch: ${it.message}" })
        }
    }

    fun clearMessage() { message.value = null }
}
