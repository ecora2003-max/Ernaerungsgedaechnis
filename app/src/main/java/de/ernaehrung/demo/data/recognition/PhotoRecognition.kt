package de.ernaehrung.demo.data.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PhotoText(val text: String, val imageHash: String)
interface PhotoRecognitionGateway {
    suspend fun barcodes(uri: Uri): List<String>
    suspend fun receiptText(uri: Uri): PhotoText
}

/** The system camera receives access to one private temporary file, never an application directory. */
class CaptureFiles(private val context: Context) {
    fun create(): String {
        val name = "${UUID.randomUUID()}.jpg"
        file(name).also { it.parentFile!!.mkdirs(); it.createNewFile() }
        return name
    }
    fun uri(name: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.recognition-files", file(name))
    fun remove(name: String) { file(name).delete() }
    private fun file(name: String): File {
        require(name.matches(Regex("[a-f0-9-]{36}\\.jpg"))) { "Ungültiger temporärer Bildname." }
        return File(context.cacheDir, "recognition/$name")
    }
}

/** Bounded image input and EXIF correction are shared by OCR and barcode decoding. No network calls. */
class AndroidPhotoRecognition(context: Context) : PhotoRecognitionGateway {
    private val resolver = context.applicationContext.contentResolver
    override suspend fun barcodes(uri: Uri): List<String> = withContext(Dispatchers.Default) {
        val image = decode(read(uri))
        try { BarcodePhotos.decode(image) } finally { image.recycle() }
    }
    override suspend fun receiptText(uri: Uri): PhotoText = withContext(Dispatchers.Default) {
        val bytes = read(uri); val image = decode(bytes)
        val recognizer = try { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
        catch (error: Exception) { image.recycle(); throw error }
        val task = try { recognizer.process(InputImage.fromBitmap(image, 0)) }
        catch (error: Exception) { recognizer.close(); image.recycle(); throw error }
        // Cancellation of the coroutine does not cancel ML Kit. Keep its bitmap alive until the task finishes.
        task.addOnCompleteListener { recognizer.close(); image.recycle() }
        run {
            val result = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { continuation ->
                task
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            }
            // Merge separate left/right blocks into reading rows, retaining every recognized line verbatim.
            val rows = result.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                line.boundingBox?.let { TextBox(line.text, it.left, it.top, it.right, it.bottom) }
            }
            val text = if (rows.isEmpty()) result.text else OcrRows.read(rows)
            require(text.isNotBlank()) { "Kein Bontext lesbar. Bild schärfer aufnehmen oder Text manuell eingeben." }
            require(text.length <= ReceiptParser.MAX_TEXT) { "Zu viel Text im Bild. Einen einzelnen Bon aufnehmen." }
            PhotoText(text, sha256(bytes))
        }
    }

    private suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Bild über Kamera oder System-Fotoauswahl öffnen." }
        requireNotNull(resolver.openInputStream(uri)) { "Bild konnte nicht geöffnet werden." }.use { stream ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break
                require(out.size() + n <= MAX_BYTES) { "Bild ist zu groß (höchstens 12 MB). Ausschnitt oder kleineres Bild wählen." }
                out.write(buffer, 0, n)
            }
            out.toByteArray().also { require(it.isNotEmpty()) { "Bilddatei ist leer." } }
        }
    }

    companion object {
        const val MAX_BYTES = 12 * 1024 * 1024
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun decode(bytes: ByteArray): Bitmap {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Bilddatei ist leer oder zu groß." }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..30_000 && bounds.outHeight in 1..30_000) { "Bildformat oder Abmessungen nicht unterstützt." }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "Bild konnte nicht dekodiert werden." }
            val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
            }
            if (matrix.isIdentity) return image
            return Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true).also { if (it !== image) image.recycle() }
        }
    }
}

/** Pure Java ZXing decoding also runs in the automated bitmap tests. */
object BarcodePhotos {
    fun decode(image: Bitmap): List<String> {
        require(image.width <= 3000 && image.height <= 3000) { "Barcodebild zuerst verkleinern." }
        val row = IntArray(image.width); val luminance = ByteArray(image.width * image.height)
        for (y in 0 until image.height) {
            image.getPixels(row, 0, image.width, 0, y, image.width, 1)
            for (x in row.indices) {
                val pixel = row[x]; val red = pixel shr 16 and 255; val green = pixel shr 8 and 255; val blue = pixel and 255
                luminance[y * image.width + x] = ((red + green * 2 + blue) / 4).toByte()
            }
        }
        var source: LuminanceSource = PhotoLuminance(luminance, image.width, image.width, image.height)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to
            listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.ITF))
        val found = linkedMapOf<String, String>()
        // Rotated photos are common, including images without correct EXIF metadata.
        for (rotation in 0..3) {
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply { setHints(hints) }
            val results = try { GenericMultipleBarcodeReader(reader).decodeMultiple(bitmap, hints).toList() }
                catch (_: NotFoundException) { emptyList() }
            results.forEach { result -> runCatching { Gtin.parse(result.text) }.getOrNull()?.let { found[it.canonical] = it.printed } }
            if (rotation < 3) source = source.rotateCounterClockwise()
        }
        return found.values.toList()
    }
}

/** Cropping must also remain supported after rotation: the multiple-code reader recursively crops its input. */
private class PhotoLuminance(private val data: ByteArray, private val stride: Int, width: Int, height: Int,
    private val left: Int = 0, private val top: Int = 0) : LuminanceSource(width, height) {
    override fun getRow(y: Int, row: ByteArray?): ByteArray = (row?.takeIf { it.size >= width } ?: ByteArray(width)).also {
        System.arraycopy(data, (top + y) * stride + left, it, 0, width)
    }
    override fun getMatrix(): ByteArray = if (left == 0 && top == 0 && width == stride && height * stride == data.size) data
        else ByteArray(width * height).also { out -> for (y in 0 until height) System.arraycopy(data, (top + y) * stride + left, out, y * width, width) }
    override fun isCropSupported() = true
    override fun crop(left: Int, top: Int, width: Int, height: Int): LuminanceSource {
        require(left >= 0 && top >= 0 && width > 0 && height > 0 && left + width <= this.width && top + height <= this.height)
        return PhotoLuminance(data, stride, width, height, this.left + left, this.top + top)
    }
    override fun isRotateSupported() = true
    override fun rotateCounterClockwise(): LuminanceSource {
        val original = matrix; val rotated = ByteArray(original.size)
        for (y in 0 until height) for (x in 0 until width) rotated[(width - 1 - x) * height + y] = original[y * width + x]
        return PhotoLuminance(rotated, height, height, width)
    }
}

data class TextBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
object OcrRows {
    fun read(boxes: List<TextBox>): String {
        val rows = mutableListOf<MutableList<TextBox>>()
        boxes.filter { it.text.isNotBlank() }.sortedWith(compareBy({ (it.top.toLong() + it.bottom) / 2 }, { it.left })).forEach { box ->
            val center = (box.top.toLong() + box.bottom) / 2
            val row = rows.lastOrNull()
            val reference = row?.firstOrNull()
            if (reference != null && kotlin.math.abs(center - (reference.top.toLong() + reference.bottom) / 2) <=
                minOf((box.bottom - box.top).coerceAtLeast(1), (reference.bottom - reference.top).coerceAtLeast(1)) / 2) row.add(box)
            else rows.add(mutableListOf(box))
        }
        return rows.joinToString("\n") { it.sortedBy(TextBox::left).joinToString(" ") { box -> box.text } }
    }
}
