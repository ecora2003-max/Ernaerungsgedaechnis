package de.ernaehrung.demo.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.offers.OfferJson
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Optional user-supplied key, never embedded in APK, Room/export, SavedState,
 * logs or model UI state. Mobile storage does not replace server-side key
 * protection. Mode persistence never restores an inference/data approval. */
internal class PrivateGeminiSettings private constructor(context: Context) {
    companion object {
        @Volatile private var shared: PrivateGeminiSettings? = null
        fun forApp(context: Context) = shared ?: synchronized(this) {
            shared ?: PrivateGeminiSettings(context.applicationContext).also { shared = it }
        }
    }
    data class Settings(val mode: DirectRecipeMode = DirectRecipeMode.NANO3, val key: String? = null,
        val model: String? = null, val revision: String = "unconfigured") {
        override fun toString() = "GeminiSettings(secrets redacted)"
    }
    private val file = AtomicFile(File(context.noBackupFilesDir, "gemini-config-v1.bin"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("eg_gemini_config_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("eg_gemini_config_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun read(): Settings {
        if (!file.baseFile.exists()) return Settings()
        require(file.baseFile.length() in 29..32768)
        val raw = file.readFully(); val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        cipher.updateAAD("EG_GEMINI_CONFIG_1".toByteArray())
        val clear = cipher.doFinal(raw, 12, raw.size - 12)
        return try {
            val data = OfferJson.read(clear.toString(Charsets.UTF_8)); require(data["format"] == "eg-gemini-config-1")
            Settings(DirectRecipeMode.valueOf(data["mode"] as String), data["key"] as? String, data["model"] as? String,
                data["revision"] as String)
        } finally { clear.fill(0); raw.fill(0) }
    }
    private fun write(value: Settings) {
        val clear = JsonOutput.encode(mapOf("format" to "eg-gemini-config-1", "mode" to value.mode.name,
            "key" to value.key, "model" to value.model, "revision" to value.revision)).toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("EG_GEMINI_CONFIG_1".toByteArray())
        val sealed = try { cipher.iv + cipher.doFinal(clear) } finally { clear.fill(0) }
        var stream: java.io.FileOutputStream? = null
        try { stream = file.startWrite(); stream.write(sealed); file.finishWrite(stream) }
        catch (failure: Exception) { stream?.let(file::failWrite); throw failure }
        finally { sealed.fill(0) }
    }
    @Synchronized fun mode(mode: DirectRecipeMode): Settings = read().copy(mode = mode, revision = UUID.randomUUID().toString()).also(::write)
    @Synchronized fun saveKey(key: String): Settings {
        require(key.length in 20..512 && key.all { it.code in 33..126 }) { "API-Schlüssel prüfen." }
        return read().copy(key = key, model = null, revision = UUID.randomUUID().toString()).also(::write)
    }
    @Synchronized fun model(model: String): Settings {
        require(GeminiCloudClient.validModelName(model))
        return read().copy(model = model, revision = UUID.randomUUID().toString()).also(::write)
    }
    @Synchronized fun clear(): Settings { file.delete(); return Settings() }
}
