package com.pdfeditable.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/** Page size in PDF points, as displayed (rotation already applied). */
data class PageSize(val widthPt: Float, val heightPt: Float) {
    val aspectRatio: Float get() = widthPt / heightPt
}

/**
 * Renders pages of a PDF file to bitmaps using the platform [PdfRenderer].
 *
 * [PdfRenderer] is not thread safe and only one page may be open at a time, so every
 * access is serialised with a [Mutex]. Rendered bitmaps are kept in a small memory cache
 * keyed by page index and pixel width so scrolling back and forth does not re-render.
 */
class PdfPageRenderer(file: File) : Closeable {

    private val fileDescriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fileDescriptor)
    private val mutex = Mutex()

    val pageCount: Int = renderer.pageCount

    /** Page sizes are read once up-front; they are needed for layout before any rendering. */
    val pageSizes: List<PageSize> = List(pageCount) { index ->
        renderer.openPage(index).use { page ->
            PageSize(page.width.toFloat(), page.height.toFloat())
        }
    }

    private val cache = object : LruCache<String, Bitmap>(cacheBudgetBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Renders [pageIndex] so that its width is [widthPx] pixels. Height follows the page's
     * aspect ratio. Returns a cached bitmap when one exists.
     */
    suspend fun render(pageIndex: Int, widthPx: Int): Bitmap {
        val clampedWidth = widthPx.coerceIn(MIN_RENDER_WIDTH, MAX_RENDER_WIDTH)
        val key = "$pageIndex@$clampedWidth"
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            mutex.withLock {
                cache.get(key)?.let { return@withLock it }
                val size = pageSizes[pageIndex]
                val heightPx = (clampedWidth / size.aspectRatio).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(clampedWidth, heightPx, Bitmap.Config.ARGB_8888)
                // PdfRenderer composites over whatever is in the bitmap; PDFs expect white paper.
                bitmap.eraseColor(Color.WHITE)
                renderer.openPage(pageIndex).use { page ->
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                cache.put(key, bitmap)
                bitmap
            }
        }
    }

    override fun close() {
        cache.evictAll()
        runCatching { renderer.close() }
        runCatching { fileDescriptor.close() }
    }

    private companion object {
        const val MIN_RENDER_WIDTH = 64

        /** ARGB_8888 at 2560 px wide is ~35 MB for a Letter page; beyond that the bitmap is stretched. */
        const val MAX_RENDER_WIDTH = 2560

        /** Use up to a quarter of the heap for page bitmaps, but never more than 96 MB. */
        fun cacheBudgetBytes(): Int {
            val quarterHeap = Runtime.getRuntime().maxMemory() / 4
            return quarterHeap.coerceAtMost(96L * 1024 * 1024).toInt()
        }
    }
}
