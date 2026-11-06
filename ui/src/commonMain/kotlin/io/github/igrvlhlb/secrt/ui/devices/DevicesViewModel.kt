package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.igrvlhlb.secrt.lan.LanException
import io.github.igrvlhlb.secrt.lan.LanHost
import io.github.igrvlhlb.secrt.lan.LanProgress
import io.github.igrvlhlb.secrt.lan.LanSync
import io.github.igrvlhlb.secrt.lan.PairingResult
import io.github.igrvlhlb.secrt.lan.SyncStats
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.Peer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State of the Devices screen. On a computer it also owns the [host], which listens for as long
 * as the screen (and so this ViewModel) exists.
 */
internal class DevicesViewModel(private val lan: LanSync, store: DiaryStore, computer: Boolean) : ViewModel() {

    val peers: StateFlow<List<Peer>> = store.peers.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val host: LanHost? = if (computer) lan.host() else null

    /** What the phone is doing right now; `null` when idle. */
    var progress by mutableStateOf<String?>(null)
        private set

    /** Identity key of the peer being synced. */
    var syncing by mutableStateOf<String?>(null)
        private set

    /** Something to show in a dialog: outcomes of pairing, and errors. */
    var notice by mutableStateOf<Notice?>(null)

    data class Notice(val title: String, val text: String)

    private val _snackbar = Channel<String>(Channel.BUFFERED)
    val snackbar = _snackbar.receiveAsFlow()

    init {
        host?.let { host ->
            viewModelScope.launch { host.run() }
            viewModelScope.launch {
                host.events.collect { event ->
                    when (event) {
                        is LanHost.Event.Paired -> _snackbar.send("Paired with ${event.peerName}")
                        is LanHost.Event.Synced -> _snackbar.send("Synced with ${event.peerName}: ${event.stats.describe()}")
                        is LanHost.Event.Failed -> _snackbar.send(event.message)
                        is LanHost.Event.DiaryReceived -> Unit
                    }
                }
            }
        }
        if (!computer) {
            // A code that arrived some other way than the scanner (debug builds).
            viewModelScope.launch {
                lan.pendingPairingLink.filterNotNull().collect { link ->
                    lan.pendingPairingLink.value = null
                    pair(link)
                }
            }
        }
    }

    fun pair(link: String) {
        if (progress != null) return
        progress = "Connecting…"
        viewModelScope.launch {
            try {
                val result = lan.pair(link) { progress = it.label() }
                notice = when (result) {
                    is PairingResult.Paired -> Notice("Paired with ${result.peerName}", "Synced: ${result.stats.describe()}.")
                    is PairingResult.DiarySent -> Notice(
                        "Diary sent to ${result.peerName}",
                        "To open it there, type this diary's password (or recovery key) on ${result.peerName}.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LanException) {
                notice = Notice("Couldn't pair", e.message.orEmpty())
            } catch (e: Exception) {
                notice = Notice("Couldn't pair", e.message ?: "Something went wrong")
            } finally {
                progress = null
            }
        }
    }

    fun sync(peer: Peer) {
        if (syncing != null || progress != null) return
        syncing = peer.identityKey
        viewModelScope.launch {
            try {
                val stats = lan.sync(peer)
                _snackbar.send("Synced with ${peer.name}: ${stats.describe()}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice("Couldn't sync with ${peer.name}", e.message ?: "Something went wrong")
            } finally {
                syncing = null
            }
        }
    }

    fun forget(peer: Peer) {
        viewModelScope.launch { lan.forget(peer) }
    }

    fun showCode() {
        viewModelScope.launch { host?.showInvite() }
    }

    fun hideCode() {
        host?.hideInvite()
    }

    private fun LanProgress.label() = when (this) {
        LanProgress.Connecting -> "Connecting…"
        LanProgress.SendingDiary -> "Sending the diary…"
        LanProgress.Syncing -> "Syncing…"
    }
}

internal fun SyncStats.describe(): String = when {
    received == 0 && sent == 0 -> "already up to date"
    else -> "$received ${if (received == 1) "change" else "changes"} received, $sent sent"
}
