package com.pdfeditable.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pdfeditable.model.Annotation
import com.pdfeditable.model.SignatureAnnotation
import com.pdfeditable.model.TextAnnotation
import com.pdfeditable.pdf.PageSize
import com.pdfeditable.pdf.PdfPageRenderer
import kotlinx.coroutines.delay

/** Callbacks a page needs to report user interaction. All coordinates are page points. */
class PageCallbacks(
    val onTapEmpty: (pageIndex: Int, x: Float, y: Float) -> Unit,
    val onTapAnnotation: (id: String) -> Unit,
    val onDragStart: (id: String) -> Unit,
    val onDrag: (id: String, x: Float, y: Float) -> Unit,
    val onPinch: (zoomChange: Float) -> Unit,
)

/**
 * One page of the document: the rendered bitmap, the annotation overlay and the gesture
 * handling that lets the user tap to add, tap to select, drag to move and pinch to zoom.
 */
@Composable
fun PdfPage(
    pageIndex: Int,
    pageSize: PageSize,
    renderer: PdfPageRenderer,
    width: Dp,
    annotations: List<Annotation>,
    selectedId: String?,
    callbacks: PageCallbacks,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }
    val height = width / pageSize.aspectRatio

    // Keep whatever we rendered last while a sharper version is being produced, so pinch
    // zooming shows a stretched bitmap immediately instead of a blank page.
    var bitmap by remember(pageIndex, renderer) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pageIndex, renderer, widthPx) {
        if (bitmap != null) delay(RENDER_DEBOUNCE_MS)
        bitmap = renderer.render(pageIndex, widthPx)
    }

    val currentAnnotations by rememberUpdatedState(annotations)
    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentScale by rememberUpdatedState(widthPx / pageSize.widthPt)
    val textMeasurer = rememberTextMeasurer()
    val selectionColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .size(width, height)
            .background(Color.White)
            .pointerInput(pageIndex) {
                awaitEachGesture {
                    handleGesture(
                        pageIndex = pageIndex,
                        scale = currentScale,
                        annotations = currentAnnotations,
                        callbacks = currentCallbacks,
                        touchSlop = viewConfiguration.touchSlop,
                    )
                }
            }
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = size.width / pageSize.widthPt
            for (annotation in annotations) {
                when (annotation) {
                    is TextAnnotation -> drawTextAnnotation(annotation, scale, textMeasurer)
                    is SignatureAnnotation -> drawSignatureAnnotation(annotation, scale)
                }
                if (annotation.id == selectedId) drawSelection(annotation, scale, selectionColor)
            }
        }
    }
}

private const val RENDER_DEBOUNCE_MS = 120L

/**
 * A single gesture from first finger down to last finger up.
 *
 * Only pinches and annotation drags consume pointer events; plain single-finger drags are
 * left alone so the surrounding list keeps scrolling normally.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleGesture(
    pageIndex: Int,
    scale: Float,
    annotations: List<Annotation>,
    callbacks: PageCallbacks,
    touchSlop: Float,
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    val downPoint = down.position / scale
    val slopPt = HIT_SLOP_PX / scale
    // Topmost (last drawn) annotation wins when several overlap.
    val hit = annotations.lastOrNull { it.contains(downPoint.x, downPoint.y, slopPt) }
    val startX = hit?.x ?: 0f
    val startY = hit?.y ?: 0f

    var pastSlop = false
    var dragging = false
    var pinching = false

    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }

        if (pressed.isEmpty()) {
            val cancelled = event.changes.any { it.isConsumed }
            if (!pastSlop && !pinching && !cancelled) {
                if (hit != null) callbacks.onTapAnnotation(hit.id)
                else callbacks.onTapEmpty(pageIndex, downPoint.x, downPoint.y)
                event.changes.forEach { it.consume() }
            }
            return
        }

        if (pressed.size >= 2) {
            pinching = true
            dragging = false
            val zoomChange = event.calculateZoom()
            if (zoomChange != 1f) callbacks.onPinch(zoomChange)
            event.changes.forEach { it.consume() }
            continue
        }

        // One finger left after a pinch: ignore until the gesture ends.
        if (pinching) continue

        val change = pressed.first()
        val delta = change.position - down.position
        if (!pastSlop && delta.getDistance() > touchSlop) {
            pastSlop = true
            if (hit != null && !change.isConsumed) {
                dragging = true
                callbacks.onDragStart(hit.id)
            }
        }
        if (dragging) {
            if (change.isConsumed) {
                dragging = false
                continue
            }
            callbacks.onDrag(hit!!.id, startX + delta.x / scale, startY + delta.y / scale)
            change.consume()
        }
    }
}

private const val HIT_SLOP_PX = 24f

// -------------------------------------------------------------------------------------------
// Drawing
// -------------------------------------------------------------------------------------------

private fun DrawScope.drawTextAnnotation(annotation: TextAnnotation, scale: Float, measurer: TextMeasurer) {
    val style = TextStyle(
        color = Color.Black,
        fontSize = (annotation.fontSize * scale).toSp(),
        fontFamily = FontFamily.SansSerif,
    )
    val lineHeightPx = annotation.lineHeight * scale
    annotation.lines.forEachIndexed { index, line ->
        drawText(
            textMeasurer = measurer,
            text = line,
            style = style,
            topLeft = Offset(annotation.x * scale, (annotation.y * scale) + index * lineHeightPx),
            softWrap = false,
        )
    }
}

private fun DrawScope.drawSignatureAnnotation(annotation: SignatureAnnotation, scale: Float) {
    val left = annotation.x * scale
    val top = annotation.y * scale
    val w = annotation.width * scale
    val h = annotation.height * scale
    val strokeWidth = (annotation.signature.strokeWidth * w).coerceAtLeast(1f)
    val path = Path()
    for (stroke in annotation.signature.strokes) {
        if (stroke.isEmpty()) continue
        path.reset()
        path.moveTo(left + stroke[0].x * w, top + stroke[0].y * h)
        if (stroke.size == 1) {
            path.lineTo(left + stroke[0].x * w + 0.1f, top + stroke[0].y * h)
        } else {
            for (p in stroke.drop(1)) path.lineTo(left + p.x * w, top + p.y * h)
        }
        drawPath(
            path = path,
            color = Color.Black,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private fun DrawScope.drawSelection(annotation: Annotation, scale: Float, color: Color) {
    val pad = 4f * scale
    val topLeft = Offset(annotation.x * scale - pad, annotation.y * scale - pad)
    val size = Size(annotation.width * scale + pad * 2, annotation.height * scale + pad * 2)
    drawRoundRect(
        color = color.copy(alpha = 0.12f),
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(pad),
    )
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(pad),
        style = Stroke(
            width = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
        ),
    )
}

