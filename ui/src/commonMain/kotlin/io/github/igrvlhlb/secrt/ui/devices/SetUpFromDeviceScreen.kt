package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.igrvlhlb.secrt.lan.LanProgress
import io.github.igrvlhlb.secrt.lan.LanSync
import io.github.igrvlhlb.secrt.ui.resources.*
import io.github.igrvlhlb.secrt.ui.text.UiText
import io.github.igrvlhlb.secrt.ui.text.string
import io.github.igrvlhlb.secrt.ui.text.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * First run on a device that scans codes (a phone): scans the code shown by a device that has the
 * diary, receives the diary, and opens it with its password. The other way around from
 * [JoinFromPhoneScreen], which a computer uses since it can't scan.
 */
@Composable
fun SetUpFromDeviceScreen(lan: LanSync, onCancel: () -> Unit) {
    val pending by lan.pendingJoin.collectAsStateWithLifecycle()
    pending?.let { join ->
        OpenReceivedDiaryScreen(lan, join, onCancel)
        return
    }

    val vm = viewModel { SetUpFromDeviceViewModel(lan) }
    // A code that arrived some other way than the scanner (debug builds).
    LaunchedEffect(vm) {
        lan.pendingPairingLink.filterNotNull().collect { link ->
            lan.pendingPairingLink.value = null
            vm.join(link)
        }
    }
    val back = {
        vm.reset()
        onCancel()
    }
    val progress = vm.progress
    val error = vm.error
    when {
        progress != null -> StatusPage(stringResource(Res.string.setup_device_progress_title), progress.string(), busy = true) {
            TextButton(onClick = back) { Text(stringResource(Res.string.action_cancel)) }
        }
        error != null -> StatusPage(stringResource(Res.string.setup_device_failed_title), error.string(), busy = false) {
            Button(onClick = vm::reset) { Text(stringResource(Res.string.setup_device_scan_again)) }
            TextButton(onClick = back) { Text(stringResource(Res.string.action_back)) }
        }
        else -> ScanScreen(
            instructions = stringResource(Res.string.setup_device_instructions),
            onScanned = vm::join,
            onBack = back,
        )
    }
}

/** Runs the transfer outside the composition, so it survives the activity being recreated (e.g. rotation). */
internal class SetUpFromDeviceViewModel(private val lan: LanSync) : ViewModel() {

    var progress by mutableStateOf<UiText?>(null)
        private set

    var error by mutableStateOf<UiText?>(null)
        private set

    private var job: Job? = null

    /** Connects to the device that showed [link] and receives its diary; see [LanSync.pendingJoin]. */
    fun join(link: String) {
        if (job?.isActive == true) return
        error = null
        progress = LanProgress.Connecting.label()
        job = viewModelScope.launch {
            try {
                lan.join(link) { progress = it.label() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.toUiText()
            } finally {
                progress = null
            }
        }
    }

    /** Stops a transfer in progress (what was received is deleted) and forgets any error. */
    fun reset() {
        job?.cancel()
        job = null
        progress = null
        error = null
    }
}

@Composable
private fun StatusPage(title: String, text: String, busy: Boolean, actions: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (busy) CircularProgressIndicator()
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(text, textAlign = TextAlign.Center, color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            actions()
        }
    }
}
