package de.ernaehrung.demo

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.oned.EAN13Writer
import de.ernaehrung.demo.data.recognition.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an actual Android device/emulator. Never count these as executed by the Robolectric unit task. */
@RunWith(AndroidJUnit4::class)
class RecognitionOnDeviceTest {
    @Test fun bundledMlKitReadsSyntheticReceiptOnDeviceWithoutCloudOcr() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext; val files = CaptureFiles(target); val name = files.create()
        try {
            target.contentResolver.openOutputStream(files.uri(name))!!.use { output ->
                instrumentation.context.assets.open("recognition/receipt-test.png").use { it.copyTo(output) }
            }
            val result = AndroidPhotoRecognition(target).receiptText(files.uri(name))
            val parsed = ReceiptParser.parse(result.text)
            assertEquals("TESTMARKT", parsed.merchant); assertEquals("2026-10-30", parsed.purchasedOn)
            assertEquals(497L, parsed.totalCents); assertEquals(2, parsed.lines.size)
            assertTrue(parsed.lines.any { it.name.contains("TOFU", true) })
            assertTrue(result.imageHash.matches(Regex("[a-f0-9]{64}")))
        } finally { files.remove(name) }
    }
    @Test fun systemImageUriBarcodePipelineReturnsActualCodeOnDevice() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext; val files = CaptureFiles(target); val name = files.create()
        val bits = EAN13Writer().encode("4006381333931", BarcodeFormat.EAN_13, 800, 300)
        val pixels = IntArray(bits.width * bits.height) { n -> if (bits[n % bits.width, n / bits.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        val image = Bitmap.createBitmap(pixels, bits.width, bits.height, Bitmap.Config.ARGB_8888)
        try {
            target.contentResolver.openOutputStream(files.uri(name))!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(listOf("4006381333931"), AndroidPhotoRecognition(target).barcodes(files.uri(name)))
        } finally { image.recycle(); files.remove(name) }
    }
}
