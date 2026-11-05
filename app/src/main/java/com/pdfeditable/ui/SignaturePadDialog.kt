package com.pdfeditable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pdfeditable.R
import com.pdfeditable.model.NormPoint
import com.pdfeditable.model.Signature

/**
 * Full-width drawing surface for capturing a signature with a finger or stylus.
 * [previous] lets the user stamp the same signature again without redrawing it.
 */
@Composable
fun SignaturePadDialog(
    previous: Signature?,
    onConfirm: (Signature) -> Unit,
    onDismiss: () -> Unit,
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val strokeWidthPx = with(LocalDensity.current) { PEN_WIDTH_DP.dp.toPx() }
    val inkColor = Color(0xFF101828)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.dialog_signature_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(PAD_ASPECT_RATIO)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current = listOf(it) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    current = current + change.position
                                },
                                onDragEnd = {
                                    if (current.isNotEmpty()) strokes.add(current)
                                    current = emptyList()
                                },
                                onDragCancel = {
                                    if (current.isNotEmpty()) strokes.add(current)
                                    current = emptyList()
                                },
                            )
                        }
                ) {
                    // Baseline guide so signatures come out roughly level.
                    drawLine(
                        color = Color(0xFFD0D5DD),
                        start = Offset(size.width * 0.08f, size.height * 0.72f),
                        end = Offset(size.width * 0.92f, size.height * 0.72f),
                        strokeWidth = 2f,
                    )
                    val style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    for (stroke in strokes + listOf(current)) {
                        if (stroke.isEmpty()) continue
                        val path = Path().apply {
                            moveTo(stroke[0].x, stroke[0].y)
                            if (stroke.size == 1) lineTo(stroke[0].x + 0.1f, stroke[0].y)
                            for (p in stroke.drop(1)) lineTo(p.x, p.y)
                        }
                        drawPath(path, inkColor, style = style)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { strokes.clear(); current = emptyList() }) {
                        Text(stringResource(R.string.dialog_clear))
                    }
                    if (previous != null) {
                        OutlinedButton(onClick = { onConfirm(previous) }) {
                            Text(stringResource(R.string.dialog_signature_reuse))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                    Button(
                        onClick = { toSignature(strokes, strokeWidthPx)?.let(onConfirm) },
                        enabled = strokes.isNotEmpty(),
                    ) { Text(stringResource(R.string.dialog_done)) }
                }
            }
        }
    }
}

private const val PEN_WIDTH_DP = 3f
private const val PAD_ASPECT_RATIO = 2.2f

/**
 * Normalises raw strokes into the unit square around their bounding box. The box is padded
 * by the pen width so round line caps are not clipped when the signature is rasterised.
 */
private fun toSignature(strokes: List<List<Offset>>, penWidthPx: Float): Signature? {
    val points = strokes.flatten()
    if (points.isEmpty()) return null
    val pad = penWidthPx
    val minX = points.minOf { it.x } - pad
    val maxX = points.maxOf { it.x } + pad
    val minY = points.minOf { it.y } - pad
    val maxY = points.maxOf { it.y } + pad
    val width = (maxX - minX).coerceAtLeast(1f)
    val height = (maxY - minY).coerceAtLeast(1f)
    return Signature(
        strokes = strokes.map { stroke ->
            stroke.map { NormPoint((it.x - minX) / width, (it.y - minY) / height) }
        },
        aspectRatio = width / height,
        strokeWidth = penWidthPx / width,
    )
}
