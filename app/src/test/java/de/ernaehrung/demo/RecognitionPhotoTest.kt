package de.ernaehrung.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.oned.EAN13Writer
import de.ernaehrung.demo.data.recognition.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecognitionPhotoTest {
    @Before fun attachProviderToThisTestApplication() {
        // Robolectric changes the private data directory between methods. Reattach through the public
        // Android API, as process startup does, rather than retaining FileProvider's previous path cache.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${context.packageName}.recognition-files"
        val info = requireNotNull(context.packageManager.resolveContentProvider(authority, android.content.pm.PackageManager.GET_META_DATA))
        val provider = FileProvider().also { it.attachInfo(context, info) }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)
    }
    private fun barcode(code: String): Bitmap {
        val bits = EAN13Writer().encode(code, BarcodeFormat.EAN_13, 600, 220, mapOf(EncodeHintType.MARGIN to 24))
        val pixels = IntArray(bits.width * bits.height) { n -> if (bits[n % bits.width, n / bits.width]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, bits.width, bits.height, Bitmap.Config.ARGB_8888)
    }
    private fun invalid(block: () -> Unit) { try { block(); fail("Expected invalid input") } catch (_: IllegalArgumentException) { } }

    @Test fun actualBarcodePixelsDecodeAfterPngRoundTripAndNinetyDegreeRotation() {
        val image = barcode("4006381333931")
        try {
            val bytes = ByteArrayOutputStream().use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
            val decoded = AndroidPhotoRecognition.decode(bytes)
            try { assertEquals(listOf("4006381333931"), BarcodePhotos.decode(decoded)) } finally { decoded.recycle() }
            val rotated = Bitmap.createBitmap(image, 0, 0, image.width, image.height, Matrix().apply { postRotate(90f) }, false)
            try { assertEquals(listOf("4006381333931"), BarcodePhotos.decode(rotated)) } finally { rotated.recycle() }
        } finally { image.recycle() }
    }
    @Test fun multipleActualBarcodesAreReturnedAsChoicesAndBlankImageStaysUnknown() {
        val a = barcode("4006381333931"); val b = barcode("3017620422003")
        val combined = Bitmap.createBitmap(640, 540, Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(combined); canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(a, 20f, 20f, null); canvas.drawBitmap(b, 20f, 290f, null)
            assertEquals(setOf("4006381333931", "3017620422003"), BarcodePhotos.decode(combined).toSet())
            combined.eraseColor(Color.WHITE); assertTrue(BarcodePhotos.decode(combined).isEmpty())
        } finally { a.recycle(); b.recycle(); combined.recycle() }
    }
    @Test fun boundedPhotoInputRejectsJunkAndAppliesRealJpegExifOrientation() {
        invalid { AndroidPhotoRecognition.decode(byteArrayOf()) }
        invalid { AndroidPhotoRecognition.decode("not an image".toByteArray()) }
        invalid { AndroidPhotoRecognition.decode(ByteArray(AndroidPhotoRecognition.MAX_BYTES + 1)) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File.createTempFile("exif-test-", ".jpg", context.cacheDir)
        val original = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
            val decoded = AndroidPhotoRecognition.decode(file.readBytes())
            try { assertEquals(20, decoded.width); assertEquals(30, decoded.height) } finally { decoded.recycle() }
            assertEquals(AndroidPhotoRecognition.sha256(file.readBytes()), AndroidPhotoRecognition.sha256(file.readBytes()))
        } finally { original.recycle(); file.delete() }
    }
    @Test fun cameraProviderSharesOnlyScopedTemporaryFileAndCannotExposeOtherAppFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val files = CaptureFiles(context)
        val name = files.create(); val uri = files.uri(name)
        try {
            assertEquals("content", uri.scheme); assertTrue(uri.path!!.startsWith("/capture/"))
            assertTrue(java.io.File(context.cacheDir, "recognition/$name").exists())
            invalid { files.uri("../../private.db") }
            invalid { FileProvider.getUriForFile(context, "${context.packageName}.recognition-files", java.io.File(context.filesDir, "private.db")) }
        } finally { files.remove(name) }
        assertFalse(java.io.File(context.cacheDir, "recognition/$name").exists())
    }
    @Test fun actualContentUriRunsThroughBoundedPhotoReaderExifAndBarcodeDecoderWithoutDeletingSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val files = CaptureFiles(context); val name = files.create()
        val uri = files.uri(name); val image = barcode("4006381333931")
        try {
            context.contentResolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(listOf("4006381333931"), AndroidPhotoRecognition(context).barcodes(uri))
            assertTrue(java.io.File(context.cacheDir, "recognition/$name").exists())
            invalid { runBlocking { AndroidPhotoRecognition(context).barcodes(android.net.Uri.parse("https://untrusted.invalid/image.jpg")) } }
        } finally { image.recycle(); files.remove(name) }
        assertFalse(java.io.File(context.cacheDir, "recognition/$name").exists())
    }
}
