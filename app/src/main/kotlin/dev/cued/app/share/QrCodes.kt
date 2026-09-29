package dev.cued.app.share

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR encode/decode with zxing-core only (no Play Services). */
object QrCodes {
    fun encode(text: String, size: Int, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) pixels[y * size + x] = if (matrix[x, y]) dark else light
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    /** Decodes the Y plane of a CameraX frame. Returns the text or null. */
    fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val data = ByteArray(width * height)
        if (rowStride == width) {
            buffer.rewind(); buffer.get(data, 0, minOf(data.size, buffer.remaining()))
        } else {
            buffer.rewind()
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                if (buffer.remaining() < width) break
                val n = minOf(rowStride, buffer.remaining())
                buffer.get(row, 0, n)
                System.arraycopy(row, 0, data, y * width, width)
            }
        }
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
            try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text } catch (_: Exception) { null }
        } finally { reader.reset() }
    }
}
