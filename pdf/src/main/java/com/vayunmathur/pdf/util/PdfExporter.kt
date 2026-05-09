package com.vayunmathur.pdf.util

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import com.vayunmathur.pdf.model.CapturedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.math.roundToInt

suspend fun savePdfToUri(context: Context, images: List<CapturedImage>, targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
    val pdfDocument = PdfDocument()
    try {
        images.forEachIndexed { index, capturedImage ->
            val uri = capturedImage.uri
            try {
                val crop = capturedImage.cropRect
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                
                val cropWidth = if (crop != null) bitmap.width * crop.width else bitmap.width.toFloat()
                val cropHeight = if (crop != null) bitmap.height * crop.height else bitmap.height.toFloat()

                // Scale the image so its longest side matches the longest side of A4 (842 points).
                val a4LongSide = 842f
                val scale = if (cropWidth > cropHeight) {
                    a4LongSide / cropWidth
                } else {
                    a4LongSide / cropHeight
                }
                
                val targetWidth = (cropWidth * scale).toInt().coerceAtLeast(1)
                val targetHeight = (cropHeight * scale).toInt().coerceAtLeast(1)

                val pageInfo = PdfDocument.PageInfo.Builder(targetWidth, targetHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                if (crop != null) {
                    val srcRect = android.graphics.Rect(
                        (crop.left * bitmap.width).roundToInt(),
                        (crop.top * bitmap.height).roundToInt(),
                        (crop.right * bitmap.width).roundToInt(),
                        (crop.bottom * bitmap.height).roundToInt()
                    )
                    val dstRect = android.graphics.Rect(0, 0, targetWidth, targetHeight)
                    page.canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                } else {
                    val matrix = Matrix()
                    matrix.postScale(scale, scale)
                    page.canvas.drawBitmap(bitmap, matrix, null)
                }

                pdfDocument.finishPage(page)
                bitmap.recycle()
            } catch (e: Exception) {
                Log.e("PdfExporter", "Error processing image $uri", e)
            }
        }

        context.contentResolver.openFileDescriptor(targetUri, "w")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { fos ->
                pdfDocument.writeTo(fos)
            }
        }
        true
    } catch (e: Exception) {
        Log.e("PdfExporter", "Failed to save PDF", e)
        false
    } finally {
        pdfDocument.close()
    }
}
