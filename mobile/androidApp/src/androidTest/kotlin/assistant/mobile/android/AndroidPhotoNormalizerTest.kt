package assistant.mobile.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.core.photos.PHOTO_MAX_BYTES
import assistant.mobile.core.photos.PHOTO_MAX_DIMENSION
import assistant.mobile.design.GeneratedColors
import assistant.mobile.platform.AndroidPhotoNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/** Synthetic files only: no device photo permission, camera or family data. */
@RunWith(AndroidJUnit4::class)
class AndroidPhotoNormalizerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "photo-normalizer-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val background = GeneratedColors.light.getValue("photo-background").toArgb()
    private val firstColor = GeneratedColors.light.getValue("destructive").toArgb()
    private val secondColor = GeneratedColors.light.getValue("primary").toArgb()
    private val normalizer = AndroidPhotoNormalizer(context, background)

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun appliesExifRotationAndStripsGpsAndCameraMetadata() = runBlocking {
        val source = splitJpeg("rotate.jpg")
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "0/1,7/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            setAttribute(ExifInterface.TAG_MAKE, "Synthetic camera")
            setAttribute(ExifInterface.TAG_DATETIME, "2026:10:16 10:00:00")
            saveAttributes()
        }
        val photo = normalizer.normalize(Uri.fromFile(source).toString())
        assertEquals(64, photo.width)
        assertEquals(128, photo.height)
        BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size).let { upright ->
            try {
                assertPixel(firstColor, upright.getPixel(32, 16))
                assertPixel(secondColor, upright.getPixel(32, 112))
            } finally { upright.recycle() }
        }
        val exif = ExifInterface(ByteArrayInputStream(photo.jpeg))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
        assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            in listOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL))
    }

    @Test fun appliesMirroredExifOrientation() = runBlocking {
        val source = splitJpeg("mirror.jpg")
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_FLIP_HORIZONTAL.toString())
            saveAttributes()
        }
        val photo = normalizer.normalize(Uri.fromFile(source).toString())
        BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size).let { upright ->
            try {
                assertPixel(secondColor, upright.getPixel(16, 32))
                assertPixel(firstColor, upright.getPixel(112, 32))
            } finally { upright.recycle() }
        }
    }

    @Test fun hugeSourceUsesTargetDecodeAndStaysWithinUploadLimits() = runBlocking {
        // A full decode needs 432 MB. Generate compressed rows without allocating those pixels.
        val source = File(directory, "huge.png")
        syntheticPng(source, 12_000, 9_000)
        val photo = normalizer.normalize(Uri.fromFile(source).toString())
        assertEquals(PHOTO_MAX_DIMENSION, photo.width)
        assertEquals(1200, photo.height)
        assertTrue(photo.jpeg.size <= PHOTO_MAX_BYTES)
        assertEquals(0xff.toByte(), photo.jpeg[0])
        assertEquals(0xd8.toByte(), photo.jpeg[1])
    }

    @Test fun transparentPixelsAreFlattenedWithPhotoBackgroundToken() = runBlocking {
        val source = File(directory, "alpha.png")
        val bitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawRect(64f, 0f, 128f, 64f, Paint().apply { color = firstColor; alpha = 128 })
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val photo = normalizer.normalize(Uri.fromFile(source).toString())
        BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size).let { opaque ->
            try {
                val pixel = opaque.getPixel(16, 32)
                assertEquals(255, Color.alpha(pixel))
                assertTrue(abs(Color.red(pixel) - Color.red(background)) <= 2)
                assertTrue(abs(Color.green(pixel) - Color.green(background)) <= 2)
                assertTrue(abs(Color.blue(pixel) - Color.blue(background)) <= 2)
                val blended = opaque.getPixel(112, 32)
                assertEquals(255, Color.alpha(blended))
                listOf<(Int) -> Int>(Color::red, Color::green, Color::blue).forEach { channel ->
                    val expected = (channel(firstColor) * 128 + channel(background) * 127) / 255
                    assertTrue(abs(channel(blended) - expected) <= 3)
                }
            } finally { opaque.recycle() }
        }
    }

    @Test fun consumesOnlyPrivateCameraSource() = runBlocking {
        val cameraDirectory = File(context.cacheDir, "photo-camera").apply { mkdirs() }
        val source = splitJpeg("selected.jpg")
        val camera = File(cameraDirectory, "synthetic-${UUID.randomUUID()}.jpg")
        source.copyTo(camera)
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", camera)
            normalizer.normalize(uri.toString())
            assertFalse(camera.exists())
            normalizer.normalize(Uri.fromFile(source).toString())
            assertTrue(source.exists())
        } finally { camera.delete() }
    }

    private fun splitJpeg(name: String): File {
        val source = File(directory, name)
        val bitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(firstColor)
                drawRect(64f, 0f, 128f, 64f, Paint().apply { color = secondColor })
            }
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally { bitmap.recycle() }
        return source
    }

    private fun assertPixel(expected: Int, pixel: Int) {
        assertEquals(255, Color.alpha(pixel))
        listOf<(Int) -> Int>(Color::red, Color::green, Color::blue).forEach { channel ->
            assertTrue(abs(channel(pixel) - channel(expected)) <= 3)
        }
    }

    private fun syntheticPng(file: File, width: Int, height: Int) {
        val compressed = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            DeflaterOutputStream(compressed, deflater).use { output ->
                val row = ByteArray(1 + width * 4)
                for (index in 0 until width) {
                    row[1 + index * 4] = 80
                    row[2 + index * 4] = 120
                    row[3 + index * 4] = 100
                    row[4 + index * 4] = 0xff.toByte()
                }
                repeat(height) { output.write(row) }
            }
        } finally { deflater.end() }
        DataOutputStream(file.outputStream()).use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val header = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 6, 0, 0, 0)) }
            }.toByteArray()
            pngChunk(output, "IHDR", header)
            pngChunk(output, "IDAT", compressed.toByteArray())
            pngChunk(output, "IEND", byteArrayOf())
        }
    }

    private fun pngChunk(output: DataOutputStream, name: String, bytes: ByteArray) {
        val kind = name.toByteArray(Charsets.US_ASCII)
        output.writeInt(bytes.size)
        output.write(kind)
        output.write(bytes)
        output.writeInt(CRC32().apply { update(kind); update(bytes) }.value.toInt())
    }
}
