package io.github.igrvlhlb.secrt.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import io.github.igrvlhlb.secrt.model.AttachmentRef
import io.github.igrvlhlb.secrt.model.ConflictKind
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.ui.AutoLock
import io.github.igrvlhlb.secrt.ui.components.ColorDot
import io.github.igrvlhlb.secrt.ui.components.ConfirmDialog
import io.github.igrvlhlb.secrt.ui.components.DiaryDatePickerDialog
import io.github.igrvlhlb.secrt.ui.components.FolderPickerDialog
import io.github.igrvlhlb.secrt.ui.components.LabelPalette
import io.github.igrvlhlb.secrt.ui.components.LabelPickerDialog
import io.github.igrvlhlb.secrt.ui.components.formatBytes
import io.github.igrvlhlb.secrt.ui.components.longFormat
import io.github.igrvlhlb.secrt.ui.components.pathTo
import io.github.igrvlhlb.secrt.ui.components.readableWidth
import io.github.igrvlhlb.secrt.ui.platform.rememberFilePicker
import io.github.igrvlhlb.secrt.ui.resources.*
import io.github.igrvlhlb.secrt.ui.text.resolve
import io.github.igrvlhlb.secrt.ui.text.text
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private enum class EditorDialog { Date, Folder, Labels, Delete }

@Composable
fun EditorScreen(vm: EditorViewModel, store: DiaryStore, autoLock: AutoLock, onClose: () -> Unit) {
    val state = vm.state
    val folders by remember(store) { store.observeFolders() }.collectAsStateWithLifecycle(emptyList())
    val labels by remember(store) { store.observeLabels() }.collectAsStateWithLifecycle(emptyList())
    var preview by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var menu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<AttachmentRef?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val pickFile = rememberFilePicker { result ->
        autoLock.resume()
        result?.onSuccess(vm::addAttachment)?.onFailure { vm.message = text(Res.string.editor_read_failed) }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    // With "lock immediately" the diary closes right as the app leaves; the save must land first.
    DisposableEffect(vm, autoLock) { onDispose(autoLock.beforeLock(vm::saveNow)) }
    LaunchedEffect(vm.notFound) { if (vm.notFound) onClose() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it.resolve())
            vm.message = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state?.diaryDate?.longFormat().orEmpty()) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) } },
                actions = {
                    IconButton(onClick = { preview = !preview }) {
                        Icon(if (preview) Icons.Outlined.Edit else Icons.Outlined.Visibility, stringResource(if (preview) Res.string.editor_edit else Res.string.editor_preview))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(Res.string.editor_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.editor_delete_entry)) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { menu = false; dialog = EditorDialog.Delete },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).readableWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.conflict?.let { ConflictBanner(it, vm::keepThisVersion) }

            BorderlessField(
                value = state.title,
                onValueChange = { title -> vm.update { it.copy(title = title) } },
                placeholder = stringResource(Res.string.editor_title),
                textStyle = MaterialTheme.typography.headlineSmall,
                singleLine = true,
                readOnly = preview,
            )

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { dialog = EditorDialog.Date },
                    label = { Text(state.diaryDate.longFormat()) },
                    leadingIcon = { Icon(Icons.Outlined.CalendarToday, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { dialog = EditorDialog.Folder },
                    label = { Text(pathTo(state.folderId, folders).joinToString(" / ") { it.name }.ifEmpty { stringResource(Res.string.editor_no_folder) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { autoLock.pause(); pickFile() },
                    label = { Text(stringResource(Res.string.editor_attach)) },
                    leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp)) },
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.filter { it.id in state.labelIds }.forEach { label ->
                    InputChip(
                        selected = false,
                        onClick = { vm.update { it.copy(labelIds = it.labelIds - label.id) } },
                        label = { Text(label.name) },
                        leadingIcon = { ColorDot(label.color) },
                        trailingIcon = { Icon(Icons.Outlined.Close, stringResource(Res.string.editor_remove_label), Modifier.size(16.dp)) },
                    )
                }
                AssistChip(
                    onClick = { dialog = EditorDialog.Labels },
                    label = { Text(stringResource(Res.string.tab_labels)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null, Modifier.size(18.dp)) },
                )
            }

            if (state.attachments.isNotEmpty()) {
                Attachments(state.attachments, vm, onOpen = { viewing = it }, onRemove = { vm.removeAttachment(it.blobId) })
            }

            if (preview) {
                if (state.body.isBlank()) {
                    Text(stringResource(Res.string.editor_nothing_written), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Markdown(content = state.body, modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp))
                }
            } else {
                BorderlessField(
                    value = state.body,
                    onValueChange = { body -> vm.update { it.copy(body = body) } },
                    placeholder = stringResource(Res.string.editor_body_placeholder),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.heightIn(min = 320.dp),
                )
            }
        }
    }

    when (dialog) {
        null -> Unit
        EditorDialog.Date -> DiaryDatePickerDialog(state!!.diaryDate, { date -> vm.update { it.copy(diaryDate = date) } }, { dialog = null })
        EditorDialog.Folder -> FolderPickerDialog(stringResource(Res.string.editor_folder), folders, state!!.folderId, { id -> vm.update { it.copy(folderId = id) } }, { dialog = null })
        EditorDialog.Labels -> LabelPickerDialog(
            labels = labels,
            selected = state!!.labelIds,
            onToggle = { id -> vm.update { it.copy(labelIds = if (id in it.labelIds) it.labelIds - id else it.labelIds + id) } },
            onCreate = { name ->
                scope.launch {
                    val id = store.createLabel(name, LabelPalette[labels.size % LabelPalette.size])
                    vm.update { it.copy(labelIds = it.labelIds + id) }
                }
            },
            onDismiss = { dialog = null },
        )
        EditorDialog.Delete -> ConfirmDialog(
            title = stringResource(Res.string.editor_delete_title),
            text = stringResource(Res.string.editor_delete_text),
            confirmLabel = stringResource(Res.string.action_delete),
            destructive = true,
            onConfirm = { scope.launch { vm.delete(); onClose() } },
            onDismiss = { dialog = null },
        )
    }

    viewing?.let { attachment -> ImageViewer(attachment, vm, onDismiss = { viewing = null }) }
}

@Composable
private fun BorderlessField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    readOnly: Boolean = false,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, style = textStyle) },
        textStyle = textStyle,
        singleLine = singleLine,
        readOnly = readOnly,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConflictBanner(kind: ConflictKind, onKeep: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.WarningAmber, null)
                Text(stringResource(Res.string.editor_conflict_title), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                when (kind) {
                    ConflictKind.CONCURRENT_EDIT -> stringResource(Res.string.editor_conflict_concurrent)
                    ConflictKind.EDITED_AFTER_DELETE -> stringResource(Res.string.editor_conflict_deleted)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onKeep) { Text(stringResource(Res.string.editor_keep_version)) }
        }
    }
}

@Composable
private fun Attachments(attachments: List<AttachmentRef>, vm: EditorViewModel, onOpen: (AttachmentRef) -> Unit, onRemove: (AttachmentRef) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment ->
            Box {
                if (attachment.isImage) {
                    LaunchedEffect(attachment.blobId) { vm.loadImage(attachment.blobId) }
                    val image = vm.images[attachment.blobId]
                    Box(
                        Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onOpen(attachment) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (image != null) {
                            Image(image, attachment.fileName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                } else {
                    Column(
                        Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.InsertDriveFile, null)
                        Text(attachment.fileName, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(attachment.size), style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { onRemove(attachment) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        stringResource(Res.string.editor_remove_attachment),
                        tint = Color.White,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageViewer(attachment: AttachmentRef, vm: EditorViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            vm.images[attachment.blobId]?.let { Image(it, attachment.fileName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
    }
}
