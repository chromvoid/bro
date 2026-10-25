package assistant.mobile.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import assistant.mobile.core.photos.NormalizedPhoto
import assistant.mobile.core.photos.PHOTO_MAX_BYTES
import assistant.mobile.core.photos.PHOTO_MAX_DIMENSION
import assistant.mobile.core.photos.PhotoNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bounded native decoding, including HEIC, followed by a fresh opaque JPEG without EXIF/GPS. */
class AndroidPhotoNormalizer(context: Context, private val photoBackgroundArgb: Int) : PhotoNormalizer {
    private val context = context.applicationContext

    init { require(photoBackgroundArgb ushr 24 == 255) { "Photo background must be opaque" } }

    override suspend fun normalize(source: String): NormalizedPhoto = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        require(uri.scheme == "content" || uri.scheme == "file") { "A local photo is required" }
        try {
            val decoded = if (Build.VERSION.SDK_INT >= 28) decodeModern(uri) else decodeSampled(uri)
            try {
                // Always redraw, including already opaque JPEGs: no source metadata is copied.
                val scale = min(1.0, PHOTO_MAX_DIMENSION.toDouble() / max(decoded.width, decoded.height))
                val width = max(1, (decoded.width * scale).roundToInt())
                val height = max(1, (decoded.height * scale).roundToInt())
                val flattened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(flattened).apply {
                        drawColor(photoBackgroundArgb)
                        drawBitmap(decoded, null, android.graphics.Rect(0, 0, width, height),
                            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    }
                    val jpeg = ByteArrayOutputStream().use { output ->
                        check(flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
                        require(output.size() <= PHOTO_MAX_BYTES) { "Photo exceeds the upload limit" }
                        output.toByteArray()
                    }
                    NormalizedPhoto(jpeg, width, height)
                } finally { flattened.recycle() }
            } finally { decoded.recycle() }
        } catch (memory: OutOfMemoryError) {
            // A decoder failure is an attachment error, never an application crash.
            throw IllegalArgumentException("Photo could not be decoded within memory limits", memory)
        } finally {
            // Only our private camera file is consumed; a library selection is never deleted.
            if (uri.authority == "${context.packageName}.files" && uri.path?.startsWith("/camera/") == true) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }

    @android.annotation.TargetApi(28)
    private fun decodeModern(uri: Uri): Bitmap = ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(context.contentResolver, uri),
    ) { decoder, info, _ ->
        val scale = min(1.0, PHOTO_MAX_DIMENSION.toDouble() / max(info.size.width, info.size.height))
        decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()),
            max(1, (info.size.height * scale).roundToInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        // ImageDecoder applies EXIF orientation, including mirrored orientations.
    }

    /** Android 8/8.1: avoid a full-sized allocation before applying all eight EXIF orientations. */
    private fun decodeSampled(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported photo" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > PHOTO_MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Unsupported photo")
        try {
            val orientation = resolver.openInputStream(uri)?.use {
                runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            if (matrix.isIdentity) return bitmap
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (upright !== bitmap) bitmap.recycle()
            return upright
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private companion object { const val JPEG_QUALITY = 88 }
}
