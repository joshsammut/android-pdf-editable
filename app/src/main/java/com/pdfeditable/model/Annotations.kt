package com.pdfeditable.model

import java.util.UUID

/**
 * Everything the user adds on top of a page is an [Annotation].
 *
 * Positions and sizes are expressed in PDF points (1/72 inch) in a "visual" coordinate
 * space: the origin is the top-left corner of the page *as displayed* (so page rotation is
 * already applied) and y grows downwards. This matches what [android.graphics.pdf.PdfRenderer]
 * shows and makes hit-testing on screen trivial. [com.pdfeditable.pdf.PdfExporter] converts
 * these coordinates into PDF user space when the document is saved.
 */
sealed interface Annotation {
    val id: String
    val pageIndex: Int

    /** Left edge, in points, visual space. */
    val x: Float

    /** Top edge, in points, visual space. */
    val y: Float

    val width: Float
    val height: Float

    fun moveTo(x: Float, y: Float): Annotation

    /** Returns a copy scaled by [factor] around its top-left corner. */
    fun scaled(factor: Float): Annotation

    fun contains(px: Float, py: Float, slop: Float = 0f): Boolean =
        px >= x - slop && px <= x + width + slop && py >= y - slop && py <= y + height + slop
}

/** A block of text. [fontSize] is in points. Lines are separated by '\n'. */
data class TextAnnotation(
    override val pageIndex: Int,
    override val x: Float,
    override val y: Float,
    val text: String,
    val fontSize: Float,
    override val id: String = UUID.randomUUID().toString(),
) : Annotation {

    val lines: List<String> get() = text.split('\n')

    /** Line height used both on screen and when exporting. */
    val lineHeight: Float get() = fontSize * LINE_SPACING

    override val width: Float
        get() = (lines.maxOfOrNull { estimateLineWidth(it, fontSize) } ?: 0f).coerceAtLeast(fontSize)

    override val height: Float get() = lineHeight * lines.size

    override fun moveTo(x: Float, y: Float): Annotation = copy(x = x, y = y)

    override fun scaled(factor: Float): Annotation =
        copy(fontSize = (fontSize * factor).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))

    companion object {
        const val LINE_SPACING = 1.2f
        const val MIN_FONT_SIZE = 6f
        const val MAX_FONT_SIZE = 96f
        const val DEFAULT_FONT_SIZE = 14f

        /**
         * Rough width of a Helvetica-ish line: average glyph advance is about half the em.
         * Only used for hit-testing and selection outlines, so it does not need to be exact.
         */
        fun estimateLineWidth(line: String, fontSize: Float): Float = line.length * fontSize * 0.52f
    }
}

/**
 * A hand-drawn signature. Strokes are stored normalised to the unit square so the same
 * signature can be drawn at any size. [aspectRatio] is width / height of the original drawing.
 */
data class SignatureAnnotation(
    override val pageIndex: Int,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    val signature: Signature,
    override val id: String = UUID.randomUUID().toString(),
) : Annotation {

    override val height: Float get() = width / signature.aspectRatio

    override fun moveTo(x: Float, y: Float): Annotation = copy(x = x, y = y)

    override fun scaled(factor: Float): Annotation =
        copy(width = (width * factor).coerceIn(MIN_WIDTH, MAX_WIDTH))

    companion object {
        const val MIN_WIDTH = 30f
        const val MAX_WIDTH = 600f

        /** Default signature width as a fraction of the page width. */
        const val DEFAULT_WIDTH_FRACTION = 0.35f
    }
}

/**
 * A signature as captured on the signature pad.
 *
 * @param strokes each stroke is a polyline of points normalised into the unit square
 *                (0..1 on both axes, y downwards).
 * @param aspectRatio width / height of the bounding box of the drawing.
 * @param strokeWidth pen width as a fraction of the drawing width.
 */
data class Signature(
    val strokes: List<List<NormPoint>>,
    val aspectRatio: Float,
    val strokeWidth: Float,
)

data class NormPoint(val x: Float, val y: Float)
