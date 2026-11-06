package ai.anthracite.fasttrack.ui.nutrition

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/** Decodes meal photos (EXIF-rotated) and downsizes them to a JPEG the vision model can take. */
object MealPhotos {

    /** Longest edge sent to the model; larger only costs time on-device. */
    private const val MAX_EDGE = 1024

    fun newCaptureFile(context: Context): File {
        val dir = File(context.cacheDir, "meal_photos").apply { mkdirs() }
        return File(dir, "meal_${System.currentTimeMillis()}.jpg")
    }

    fun fromFile(file: File): ByteArray = encode(ImageDecoder.createSource(file))

    fun fromUri(resolver: ContentResolver, uri: Uri): ByteArray = encode(ImageDecoder.createSource(resolver, uri))

    private fun encode(source: ImageDecoder.Source): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = MAX_EDGE.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
