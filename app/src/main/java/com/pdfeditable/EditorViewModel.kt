package com.pdfeditable

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfeditable.model.Annotation
import com.pdfeditable.model.Signature
import com.pdfeditable.model.SignatureAnnotation
import com.pdfeditable.model.TextAnnotation
import com.pdfeditable.pdf.PageSize
import com.pdfeditable.pdf.PdfExporter
import com.pdfeditable.pdf.PdfPageRenderer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

enum class Tool { VIEW, TEXT, SIGNATURE }

/** An open PDF: where it came from, the local copy we render from, and its page geometry. */
class OpenDocument(
    val sourceUri: Uri?,
    val displayName: String,
    val file: File,
    val renderer: PdfPageRenderer,
) {
    val pageCount: Int get() = renderer.pageCount
    val pageSizes: List<PageSize> get() = renderer.pageSizes
}

data class EditorState(
    val document: OpenDocument? = null,
    val annotations: List<Annotation> = emptyList(),
    val tool: Tool = Tool.VIEW,
    val selectedId: String? = null,
    val zoom: Float = 1f,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    val canUndo: Boolean = false,
    val lastSignature: Signature? = null,
) {
    val selected: Annotation? get() = annotations.firstOrNull { it.id == selectedId }
}

/** One-shot messages for the UI (snackbars). */
sealed interface UiMessage {
    data class Text(val text: String) : UiMessage
    data class Resource(val resId: Int, val args: List<Any> = emptyList()) : UiMessage
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private val undoStack = ArrayDeque<List<Annotation>>()

    init {
        PDFBoxResourceLoader.init(application)
    }

    // ---------------------------------------------------------------------------------------
    // Opening
    // ---------------------------------------------------------------------------------------

    fun open(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val result = withContext(Dispatchers.IO) { runCatching { loadDocument(uri) } }
            result.onSuccess { document ->
                replaceDocument(document)
                _state.update { it.copy(isLoading = false) }
            }.onFailure { error ->
                _state.update { it.copy(isLoading = false) }
                val message = when (error) {
                    is SecurityException -> UiMessage.Resource(R.string.error_protected)
                    else -> UiMessage.Resource(R.string.error_open, listOf(error.localizedMessage ?: ""))
                }
                _messages.tryEmit(message)
            }
        }
    }

    private fun loadDocument(uri: Uri): OpenDocument {
        val context = getApplication<Application>()
        val file = File(context.cacheDir, "working-${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException(uri.toString())
        val renderer = PdfPageRenderer(file)
        return OpenDocument(uri, queryDisplayName(uri), file, renderer)
    }

    private fun queryDisplayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) return cursor.getString(column)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    private fun replaceDocument(document: OpenDocument) {
        val previous = _state.value.document
        undoStack.clear()
        _state.update {
            it.copy(
                document = document,
                annotations = emptyList(),
                selectedId = null,
                isDirty = false,
                canUndo = false,
                zoom = 1f,
            )
        }
        previous?.let { closeDocument(it) }
    }

    private fun closeDocument(document: OpenDocument) {
        document.renderer.close()
        document.file.delete()
    }

    // ---------------------------------------------------------------------------------------
    // Tools, zoom, selection
    // ---------------------------------------------------------------------------------------

    fun setTool(tool: Tool) {
        _state.update { it.copy(tool = tool, selectedId = null) }
    }

    fun setZoom(zoom: Float) {
        _state.update { it.copy(zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)) }
    }

    fun select(id: String?) {
        _state.update { it.copy(selectedId = id) }
    }

    // ---------------------------------------------------------------------------------------
    // Annotations
    // ---------------------------------------------------------------------------------------

    fun addText(pageIndex: Int, x: Float, y: Float, text: String, fontSize: Float) {
        if (text.isBlank()) return
        val annotation = TextAnnotation(pageIndex, x, y, text, fontSize)
        commit { it + annotation }
        _state.update { it.copy(selectedId = annotation.id, tool = Tool.VIEW) }
    }

    fun updateText(id: String, text: String, fontSize: Float) {
        if (text.isBlank()) return
        commit { list ->
            list.map { if (it.id == id && it is TextAnnotation) it.copy(text = text, fontSize = fontSize) else it }
        }
    }

    fun addSignature(pageIndex: Int, centerX: Float, centerY: Float, signature: Signature) {
        val document = _state.value.document ?: return
        val pageWidth = document.pageSizes[pageIndex].widthPt
        val width = (pageWidth * SignatureAnnotation.DEFAULT_WIDTH_FRACTION)
            .coerceIn(SignatureAnnotation.MIN_WIDTH, SignatureAnnotation.MAX_WIDTH)
        val height = width / signature.aspectRatio
        val annotation = SignatureAnnotation(
            pageIndex = pageIndex,
            x = centerX - width / 2f,
            y = centerY - height / 2f,
            width = width,
            signature = signature,
        )
        commit { it + annotation }
        _state.update { it.copy(selectedId = annotation.id, tool = Tool.VIEW, lastSignature = signature) }
    }

    /** Call once when a drag starts so the whole drag becomes a single undo step. */
    fun beginMove() {
        pushUndo(_state.value.annotations)
    }

    fun moveTo(id: String, x: Float, y: Float) {
        _state.update { state ->
            val document = state.document ?: return@update state
            state.copy(
                annotations = state.annotations.map { annotation ->
                    if (annotation.id != id) return@map annotation
                    val page = document.pageSizes[annotation.pageIndex]
                    // Keep at least a sliver of the annotation on the page so it can be grabbed again.
                    val margin = 12f
                    annotation.moveTo(
                        x.coerceIn(margin - annotation.width, page.widthPt - margin),
                        y.coerceIn(margin - annotation.height, page.heightPt - margin),
                    )
                },
                isDirty = true,
            )
        }
    }

    fun scaleSelected(factor: Float) {
        val id = _state.value.selectedId ?: return
        commit { list -> list.map { if (it.id == id) it.scaled(factor) else it } }
    }

    fun deleteSelected() {
        val id = _state.value.selectedId ?: return
        commit { list -> list.filterNot { it.id == id } }
        _state.update { it.copy(selectedId = null) }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        _state.update {
            it.copy(
                annotations = previous,
                selectedId = if (previous.any { a -> a.id == it.selectedId }) it.selectedId else null,
                isDirty = true,
                canUndo = undoStack.isNotEmpty(),
            )
        }
    }

    private fun commit(transform: (List<Annotation>) -> List<Annotation>) {
        val current = _state.value.annotations
        pushUndo(current)
        _state.update { it.copy(annotations = transform(current), isDirty = true, canUndo = true) }
    }

    private fun pushUndo(snapshot: List<Annotation>) {
        undoStack.addLast(snapshot)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        _state.update { it.copy(canUndo = true) }
    }

    // ---------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------

    /** Suggested file name for "Save as". */
    fun suggestedSaveName(): String {
        val name = _state.value.document?.displayName ?: "document.pdf"
        val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
        return if (base.endsWith("-signed")) "$base.pdf" else "$base-signed.pdf"
    }

    /** Writes back to the file that was opened. Falls back with a message if it is read-only. */
    fun saveToSource() {
        val uri = _state.value.document?.sourceUri
        if (uri == null) {
            _messages.tryEmit(UiMessage.Resource(R.string.error_not_writable))
            return
        }
        save(uri, fallbackMessageOnDenied = true)
    }

    fun save(target: Uri, fallbackMessageOnDenied: Boolean = false) {
        val document = _state.value.document ?: return
        val annotations = _state.value.annotations
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching { writeExport(document, annotations, target) }
            }
            result.onSuccess { exported ->
                // The saved file becomes the new base document, so annotations are flattened
                // and a second save will not stamp them twice.
                val renderer = withContext(Dispatchers.IO) { PdfPageRenderer(exported) }
                val name = if (target == document.sourceUri) document.displayName else queryDisplayName(target)
                val keepZoom = _state.value.zoom
                replaceDocument(OpenDocument(target, name, exported, renderer))
                _state.update { it.copy(isSaving = false, zoom = keepZoom) }
                _messages.tryEmit(UiMessage.Resource(R.string.status_saved))
            }.onFailure { error ->
                _state.update { it.copy(isSaving = false) }
                val denied = error is SecurityException ||
                    error is FileNotFoundException ||
                    (error is IOException && error.message?.contains("EACCES") == true)
                val message = if (denied && fallbackMessageOnDenied) {
                    UiMessage.Resource(R.string.error_not_writable)
                } else {
                    UiMessage.Resource(R.string.error_save, listOf(error.localizedMessage ?: ""))
                }
                _messages.tryEmit(message)
            }
        }
    }

    /** Exports to a fresh cache file first, then copies it to [target]. Returns the cache file. */
    private fun writeExport(document: OpenDocument, annotations: List<Annotation>, target: Uri): File {
        val context = getApplication<Application>()
        val exported = File(context.cacheDir, "export-${System.currentTimeMillis()}.pdf")
        try {
            exported.outputStream().use { output ->
                PdfExporter.export(document.file, annotations, output)
            }
            // "wt" truncates so an existing (longer) file does not keep stale trailing bytes.
            val output = context.contentResolver.openOutputStream(target, "wt")
                ?: throw FileNotFoundException(target.toString())
            output.use { exported.inputStream().use { input -> input.copyTo(it) } }
        } catch (error: Throwable) {
            exported.delete()
            throw error
        }
        return exported
    }

    override fun onCleared() {
        _state.value.document?.let { closeDocument(it) }
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 4f
        private const val MAX_UNDO = 50
    }
}
