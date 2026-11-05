package com.pdfeditable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfeditable.EditorState
import com.pdfeditable.EditorViewModel
import com.pdfeditable.R
import com.pdfeditable.Tool
import com.pdfeditable.UiMessage
import com.pdfeditable.model.TextAnnotation

/** Where the user tapped on a page; used to position a new annotation once a dialog confirms. */
private data class TapTarget(val pageIndex: Int, val x: Float, val y: Float)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorState,
    viewModel: EditorViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenClick: () -> Unit,
    onSaveAsClick: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    var pendingText by remember { mutableStateOf<TapTarget?>(null) }
    var pendingSignature by remember { mutableStateOf<TapTarget?>(null) }
    var editingTextId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is UiMessage.Text -> message.text
                is UiMessage.Resource -> context.getString(message.resId, *message.args.toTypedArray())
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    BackHandler(enabled = state.isDirty) { showDiscardDialog = true }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = state.document?.displayName ?: stringResource(R.string.app_name),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (state.document != null && state.isDirty) {
                                Text(
                                    text = stringResource(R.string.status_unsaved),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenClick) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.action_open))
                        }
                        IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.action_undo))
                        }
                        IconButton(
                            onClick = viewModel::saveToSource,
                            enabled = state.document != null && state.isDirty && !state.isSaving,
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.action_save))
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }, enabled = state.document != null) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_save_as)) },
                                    onClick = { showMenu = false; onSaveAsClick() },
                                )
                            }
                        }
                    },
                )
                if (state.isLoading || state.isSaving) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            if (state.document != null) {
                BottomBars(
                    state = state,
                    viewModel = viewModel,
                    onEditText = { editingTextId = it },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val document = state.document
        if (document == null) {
            EmptyState(onOpenClick = onOpenClick, modifier = Modifier.padding(padding))
        } else {
            Box(modifier = Modifier.padding(padding)) {
                PageList(
                    state = state,
                    viewModel = viewModel,
                    onTapEmpty = { page, x, y ->
                        when (state.tool) {
                            Tool.TEXT -> pendingText = TapTarget(page, x, y)
                            Tool.SIGNATURE -> pendingSignature = TapTarget(page, x, y)
                            Tool.VIEW -> viewModel.select(null)
                        }
                    },
                )
                ModeHint(state.tool, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Dialogs
    // ----------------------------------------------------------------------------------------

    pendingText?.let { target ->
        TextDialog(
            initialText = "",
            initialFontSize = TextAnnotation.DEFAULT_FONT_SIZE,
            isEditing = false,
            onConfirm = { text, size ->
                viewModel.addText(target.pageIndex, target.x, target.y, text, size)
                pendingText = null
            },
            onDismiss = { pendingText = null },
        )
    }

    val editingText = editingTextId?.let { id ->
        state.annotations.firstOrNull { it.id == id } as? TextAnnotation
    }
    if (editingText != null) {
        TextDialog(
            initialText = editingText.text,
            initialFontSize = editingText.fontSize,
            isEditing = true,
            onConfirm = { text, size ->
                viewModel.updateText(editingText.id, text, size)
                editingTextId = null
            },
            onDismiss = { editingTextId = null },
        )
    }

    pendingSignature?.let { target ->
        SignaturePadDialog(
            previous = state.lastSignature,
            onConfirm = { signature ->
                viewModel.addSignature(target.pageIndex, target.x, target.y, signature)
                pendingSignature = null
            },
            onDismiss = { pendingSignature = null },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_message)) },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onExit() }) {
                    Text(stringResource(R.string.discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

// -------------------------------------------------------------------------------------------
// Pages
// -------------------------------------------------------------------------------------------

@Composable
private fun PageList(
    state: EditorState,
    viewModel: EditorViewModel,
    onTapEmpty: (pageIndex: Int, x: Float, y: Float) -> Unit,
) {
    val document = state.document ?: return
    val listState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()

    val callbacks = remember(viewModel, onTapEmpty) {
        PageCallbacks(
            onTapEmpty = onTapEmpty,
            onTapAnnotation = { id -> viewModel.select(id) },
            onDragStart = { id -> viewModel.select(id); viewModel.beginMove() },
            onDrag = { id, x, y -> viewModel.moveTo(id, x, y) },
            onPinch = { change -> viewModel.setZoom(viewModel.state.value.zoom * change) },
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val margin = 12.dp
        val pageWidth = (maxWidth - margin * 2) * state.zoom
        val contentWidth = maxOf(pageWidth + margin * 2, maxWidth)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(horizontalScroll),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(contentWidth)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(vertical = margin),
                verticalArrangement = Arrangement.spacedBy(margin),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(count = document.pageCount, key = { it }) { index ->
                    PdfPage(
                        pageIndex = index,
                        pageSize = document.pageSizes[index],
                        renderer = document.renderer,
                        width = pageWidth,
                        annotations = state.annotations.filter { it.pageIndex == index },
                        selectedId = state.selectedId,
                        callbacks = callbacks,
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.page_indicator,
                    listState.firstVisibleItemIndex + 1,
                    document.pageCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ModeHint(tool: Tool, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = tool != Tool.VIEW, modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            tonalElevation = 2.dp,
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = stringResource(
                    when (tool) {
                        Tool.TEXT -> R.string.hint_text_mode
                        Tool.SIGNATURE -> R.string.hint_signature_mode
                        Tool.VIEW -> R.string.hint_view_mode
                    }
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// Bottom bars
// -------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomBars(
    state: EditorState,
    viewModel: EditorViewModel,
    onEditText: (id: String) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            val selected = state.selected
            AnimatedVisibility(visible = selected != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected is TextAnnotation) {
                        FilledTonalButton(onClick = { onEditText(selected.id) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.selection_edit))
                        }
                    }
                    IconButton(onClick = { viewModel.scaleSelected(1f / SCALE_STEP) }) {
                        Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.selection_smaller))
                    }
                    IconButton(onClick = { viewModel.scaleSelected(SCALE_STEP) }) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.selection_bigger))
                    }
                    IconButton(onClick = viewModel::deleteSelected) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.selection_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.select(null) }) {
                        Text(stringResource(R.string.selection_done))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    Tool.entries.forEachIndexed { index, tool ->
                        SegmentedButton(
                            selected = state.tool == tool,
                            onClick = { viewModel.setTool(tool) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = Tool.entries.size),
                            icon = {
                                SegmentedButtonDefaults.Icon(active = state.tool == tool) {
                                    Icon(
                                        imageVector = when (tool) {
                                            Tool.VIEW -> Icons.Outlined.PanTool
                                            Tool.TEXT -> Icons.Outlined.TextFields
                                            Tool.SIGNATURE -> Icons.Outlined.Draw
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.height(SegmentedButtonDefaults.IconSize),
                                    )
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    when (tool) {
                                        Tool.VIEW -> R.string.tool_view
                                        Tool.TEXT -> R.string.tool_text
                                        Tool.SIGNATURE -> R.string.tool_signature
                                    }
                                )
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { viewModel.setZoom(state.zoom / ZOOM_STEP) },
                    enabled = state.zoom > EditorViewModel.MIN_ZOOM,
                ) {
                    Icon(Icons.Outlined.ZoomOut, contentDescription = stringResource(R.string.action_zoom_out))
                }
                IconButton(
                    onClick = { viewModel.setZoom(state.zoom * ZOOM_STEP) },
                    enabled = state.zoom < EditorViewModel.MAX_ZOOM,
                ) {
                    Icon(Icons.Outlined.ZoomIn, contentDescription = stringResource(R.string.action_zoom_in))
                }
            }
        }
    }
}

private const val SCALE_STEP = 1.15f
private const val ZOOM_STEP = 1.25f

// -------------------------------------------------------------------------------------------
// Empty state
// -------------------------------------------------------------------------------------------

@Composable
private fun EmptyState(onOpenClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Draw,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(64.dp).width(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.hint_no_document),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenClick) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_open))
        }
    }
}
