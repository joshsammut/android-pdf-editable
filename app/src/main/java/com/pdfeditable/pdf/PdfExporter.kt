package com.pdfeditable.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.pdfeditable.model.Annotation
import com.pdfeditable.model.Signature
import com.pdfeditable.model.SignatureAnnotation
import com.pdfeditable.model.TextAnnotation
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Burns a list of [Annotation]s into a PDF using PdfBox-Android.
 *
 * Text is written as real text (selectable and searchable in other viewers), signatures are
 * embedded as lossless images with transparency. The output is a flattened copy; the source
 * file is never modified.
 */
object PdfExporter {

    fun export(source: File, annotations: List<Annotation>, output: OutputStream) {
        PDDocument.load(source).use { document ->
            // Allows saving documents that carry an owner password with restricted permissions.
            document.isAllSecurityToBeRemoved = true

            val byPage = annotations.groupBy { it.pageIndex }
            for ((pageIndex, pageAnnotations) in byPage) {
                if (pageIndex !in 0 until document.numberOfPages) continue
                val page = document.getPage(pageIndex)
                PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true, true
                ).use { stream ->
                    // Everything below is drawn in "visual" space: origin at the bottom-left of
                    // the page as displayed, y upwards, in points. The matrix handles rotation
                    // and crop-box offsets so the maths per annotation stays simple.
                    val visual = visualSpace(page)
                    stream.transform(visual.toUserSpace)
                    for (annotation in pageAnnotations) {
                        when (annotation) {
                            is TextAnnotation -> drawText(stream, annotation, visual.heightPt)
                            is SignatureAnnotation -> drawSignature(document, stream, annotation, visual.heightPt)
                        }
                    }
                }
            }
            document.save(output)
        }
    }

    private class VisualSpace(val widthPt: Float, val heightPt: Float, val toUserSpace: Matrix)

    /**
     * Builds the matrix that maps the displayed page (bottom-left origin, y up) onto PDF user
     * space, taking the page's /Rotate entry and crop box into account.
     */
    private fun visualSpace(page: PDPage): VisualSpace {
        val box = page.cropBox
        val llx = box.lowerLeftX
        val lly = box.lowerLeftY
        val urx = box.upperRightX
        val ury = box.upperRightY
        val boxWidth = box.width
        val boxHeight = box.height

        return when (((page.rotation % 360) + 360) % 360) {
            90 -> {
                // Displayed width is the box height. Rotate +90° then shift into the box.
                val w = boxHeight
                val h = boxWidth
                VisualSpace(w, h, Matrix(0f, 1f, -1f, 0f, llx + h, lly))
            }
            180 -> VisualSpace(boxWidth, boxHeight, Matrix(-1f, 0f, 0f, -1f, urx, ury))
            270 -> {
                val w = boxHeight
                val h = boxWidth
                VisualSpace(w, h, Matrix(0f, -1f, 1f, 0f, urx - h, ury))
            }
            else -> VisualSpace(boxWidth, boxHeight, Matrix(1f, 0f, 0f, 1f, llx, lly))
        }
    }

    private fun drawText(stream: PDPageContentStream, annotation: TextAnnotation, pageHeight: Float) {
        val font: PDFont = PDType1Font.HELVETICA
        val size = annotation.fontSize
        val ascent = font.fontDescriptor.ascent / 1000f * size
        // DeviceRGB, components in 0..1 (the int overload is deprecated in PdfBox 2.0).
        stream.setNonStrokingColor(0f, 0f, 0f)
        stream.beginText()
        stream.setFont(font, size)
        stream.setLeading(annotation.lineHeight)
        // Visual y is measured from the top; flip it and drop down by the ascent so the top of
        // the first line sits where the user placed it.
        stream.newLineAtOffset(annotation.x, pageHeight - annotation.y - ascent)
        annotation.lines.forEachIndexed { index, line ->
            if (index > 0) stream.newLine()
            stream.showText(encodable(font, line))
        }
        stream.endText()
    }

    /** Replaces characters the standard font cannot encode so saving never fails on emoji etc. */
    private fun encodable(font: PDFont, text: String): String {
        val builder = StringBuilder(text.length)
        // Iterate code points, not chars, so an emoji (a surrogate pair) becomes a single '?'.
        text.codePoints().forEach { codePoint ->
            val s = String(Character.toChars(codePoint))
            val ok = runCatching { font.encode(s) }.isSuccess
            builder.append(if (ok) s else "?")
        }
        return builder.toString()
    }

    private fun drawSignature(
        document: PDDocument,
        stream: PDPageContentStream,
        annotation: SignatureAnnotation,
        pageHeight: Float,
    ) {
        val bitmap = renderSignatureBitmap(annotation.signature)
        val image = LosslessFactory.createFromImage(document, bitmap)
        bitmap.recycle()
        val bottom = pageHeight - annotation.y - annotation.height
        stream.drawImage(image, annotation.x, bottom, annotation.width, annotation.height)
    }

    /** Rasterises the signature strokes onto a transparent bitmap. */
    fun renderSignatureBitmap(signature: Signature, widthPx: Int = SIGNATURE_RASTER_WIDTH): Bitmap {
        val heightPx = (widthPx / signature.aspectRatio).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = signature.strokeWidth * widthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        for (stroke in signature.strokes) {
            if (stroke.isEmpty()) continue
            path.rewind()
            path.moveTo(stroke[0].x * widthPx, stroke[0].y * heightPx)
            if (stroke.size == 1) {
                // A dot: draw a tiny segment so round caps produce a visible point.
                path.lineTo(stroke[0].x * widthPx + 0.01f, stroke[0].y * heightPx)
            } else {
                for (point in stroke.drop(1)) path.lineTo(point.x * widthPx, point.y * heightPx)
            }
            canvas.drawPath(path, paint)
        }
        return bitmap
    }

    private const val SIGNATURE_RASTER_WIDTH = 1200
}
