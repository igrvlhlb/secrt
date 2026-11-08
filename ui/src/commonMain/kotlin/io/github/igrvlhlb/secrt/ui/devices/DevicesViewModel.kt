package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.igrvlhlb.secrt.lan.LanException
import io.github.igrvlhlb.secrt.lan.LanHost
import io.github.igrvlhlb.secrt.lan.LanProgress
import io.github.igrvlhlb.secrt.lan.LanQuestion
import io.github.igrvlhlb.secrt.lan.LanSync
import io.github.igrvlhlb.secrt.lan.PairingResult
import io.github.igrvlhlb.secrt.lan.SyncStats
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.Peer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State of the Devices screen. It owns the [host], which listens for the user's other devices for
 * as long as the screen (and so this ViewModel) exists.
 *
 * @param canScan whether this device scans codes, and so also takes codes handed in some other way.
 */
internal class DevicesViewModel(private val lan: LanSync, store: DiaryStore, canScan: Boolean) : ViewModel() {

    val peers: StateFlow<List<Peer>> = store.peers.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val host: LanHost = lan.host()

    /** What this device is doing as the one that scanned a code; `null` when idle. */
    var progress by mutableStateOf<String?>(null)
        private set

    /** Identity key of the peer being synced. */
    var syncing by mutableStateOf<String?>(null)
        private set

    /** Something to show in a dialog: outcomes of pairing, and errors. */
    var notice by mutableStateOf<Notice?>(null)

    data class Notice(val title: String, val text: String)

    /**
     * A question from a pairing or sync this device started, waiting for [answer]. Those from
     * devices connecting to this one come through [LanHost.question].
     */
    var question by mutableStateOf<LanQuestion?>(null)
        private set

    private var pendingAnswer: CompletableDeferred<Boolean>? = null

    private val _snackbar = Channel<String>(Channel.BUFFERED)
    val snackbar = _snackbar.receiveAsFlow()

    init {
        viewModelScope.launch { host.run() }
        viewModelScope.launch {
            host.events.collect { event ->
                when (event) {
                    is LanHost.Event.Paired -> _snackbar.send("Paired with ${event.peerName}")
                    is LanHost.Event.Synced -> _snackbar.send("Synced with ${event.peerName}: ${event.stats.describe()}")
                    is LanHost.Event.Failed -> _snackbar.send(event.message)
                    is LanHost.Event.DiarySent -> notice = diarySent(event.peerName)
                    is LanHost.Event.DiaryReceived, is LanHost.Event.WaitingForAnswer -> Unit
                }
            }
        }
        if (canScan) {
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
                val result = lan.pair(link, ::ask) { progress = it.label() }
                notice = when (result) {
                    is PairingResult.Paired -> Notice("Paired with ${result.peerName}", "Synced: ${result.stats.describe()}.")
                    is PairingResult.DiarySent -> diarySent(result.peerName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LanException.Declined) {
                // The user's own "no": nothing went wrong.
                _snackbar.send(e.message.orEmpty())
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

    fun answer(accept: Boolean) {
        pendingAnswer?.complete(accept)
    }

    /** Shows [question] until it's answered, or the pairing gives up on it (timeout, lock, screen closed). */
    private suspend fun ask(question: LanQuestion): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingAnswer = answer
        this.question = question
        try {
            return answer.await()
        } finally {
            this.question = null
            pendingAnswer = null
        }
    }

    fun forget(peer: Peer) {
        viewModelScope.launch { lan.forget(peer) }
    }

    fun showCode() {
        viewModelScope.launch { host.showInvite() }
    }

    fun hideCode() {
        host.hideInvite()
    }

    private fun diarySent(peerName: String) = Notice(
        "Diary sent to $peerName",
        "To open it there, type this diary's password (or recovery key) on $peerName.",
    )
}

internal fun LanProgress.label() = when (this) {
    LanProgress.Connecting -> "Connecting…"
    LanProgress.SendingDiary -> "Sending the diary…"
    LanProgress.WaitingForAnswer -> "Waiting for an answer on the other device…"
    LanProgress.ReceivingDiary -> "Receiving the diary…"
    LanProgress.Syncing -> "Syncing…"
}

internal fun SyncStats.describe(): String = when {
    received == 0 && sent == 0 -> "already up to date"
    else -> "$received ${if (received == 1) "change" else "changes"} received, $sent sent"
}
