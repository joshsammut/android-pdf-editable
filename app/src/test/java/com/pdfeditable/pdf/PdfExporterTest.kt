package com.pdfeditable.pdf

import com.pdfeditable.model.TextAnnotation
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Checks that text ends up where the user put it on screen, whatever the page rotation and
 * crop box offset.
 *
 * The exporter writes with PdfBox-Android; this test reads the result back with the
 * reference Apache PDFBox, so the check is independent of the library under test. Its text
 * stripper reports glyph positions in "direction adjusted" coordinates: top-left origin of the
 * page as displayed, y downwards, which is exactly the visual space annotations are stored in.
 */
class PdfExporterTest {

    private val tolerance = 0.5f

    @Test
    fun textLandsAtVisualPositionForEveryRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val hits = exportAndLocate(rotation = rotation, cropBox = PDRectangle(0f, 0f, 612f, 792f))
            assertPosition(rotation, hits)
        }
    }

    @Test
    fun textLandsAtVisualPositionWithOffsetCropBox() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val hits = exportAndLocate(rotation = rotation, cropBox = PDRectangle(40f, 25f, 500f, 700f))
            assertPosition(rotation, hits)
        }
    }

    @Test
    fun multipleLinesAdvanceByLineHeight() {
        val hits = exportAndLocate(rotation = 0, cropBox = PDRectangle(0f, 0f, 612f, 792f), text = "One\nTwo")
        assertEquals(2, hits.size)
        val first = hits.first { it.text == "One" }
        val second = hits.first { it.text == "Two" }
        assertEquals(FONT_SIZE * TextAnnotation.LINE_SPACING, second.y - first.y, tolerance)
        assertEquals(first.x, second.x, tolerance)
    }

    @Test
    fun unencodableCharactersAreReplacedInsteadOfFailing() {
        val hits = exportAndLocate(rotation = 0, cropBox = PDRectangle(0f, 0f, 612f, 792f), text = "Hi 😀!")
        assertEquals("Hi ?!", hits.single().text)
    }

    private fun assertPosition(rotation: Int, hits: List<Hit>) {
        assertEquals("rotation $rotation: one line expected", 1, hits.size)
        val hit = hits.single()
        val ascent = PDType1Font.HELVETICA.fontDescriptor.ascent / 1000f * FONT_SIZE
        assertEquals("rotation $rotation: x", X, hit.x, tolerance)
        assertEquals("rotation $rotation: baseline y", Y + ascent, hit.y, tolerance)
        // The stripper reports direction in unrotated user space. Text must be rotated by the
        // same amount as the page so that it reads left-to-right once the viewer rotates it.
        assertEquals("rotation $rotation: direction", rotation.toFloat(), hit.direction, tolerance)
    }

    private class Hit(val text: String, val x: Float, val y: Float, val direction: Float)

    private fun exportAndLocate(rotation: Int, cropBox: PDRectangle, text: String = "Hello"): List<Hit> {
        val source = File.createTempFile("source", ".pdf")
        try {
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(0f, 0f, 612f, 792f))
                page.cropBox = cropBox
                page.rotation = rotation
                document.addPage(page)
                document.save(source)
            }

            val annotation = TextAnnotation(pageIndex = 0, x = X, y = Y, text = text, fontSize = FONT_SIZE)
            val exported = ByteArrayOutputStream()
            PdfExporter.export(source, listOf(annotation), exported)

            val hits = mutableListOf<Hit>()
            PDDocument.load(exported.toByteArray()).use { document ->
                val stripper = object : PDFTextStripper() {
                    override fun writeString(string: String, textPositions: List<TextPosition>) {
                        val first = textPositions.first()
                        hits += Hit(string, first.xDirAdj, first.yDirAdj, first.dir)
                    }
                }
                stripper.getText(document)
            }
            return hits
        } finally {
            source.delete()
        }
    }

    private companion object {
        const val X = 100f
        const val Y = 200f
        const val FONT_SIZE = 14f
    }
}
