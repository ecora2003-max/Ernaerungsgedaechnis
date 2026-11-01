package de.ernaehrung.demo.data.local

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.SecretKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unconfirmed manual form inputs only. Restored values are NOT independent evidence,
 * authorization, a checked checkbox, a consent, or a Room/FoodStore write.
 */
internal data class RecipeDraftEvidenceForm(
    val draftHash: String,
    val versionIds: Map<String, String>,
    val bases: Map<String, String>,
    val states: Map<String, String>,
    val allergens: Map<Pair<String, String>, Boolean?>,
    val restrictionsText: String
)

internal fun recipeDraftEvidenceDigest(json: String): String =
    MessageDigest.getInstance("SHA-256").digest(json.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal interface RecipeDraftEncryptedEvidenceCheckpoint {
    fun save(form: RecipeDraftEvidenceForm)
    fun load(): RecipeDraftEvidenceForm?
    fun discard()
}

/**
 * Separate AES-GCM key and no-backup file from the JSON checkpoint. Shares its
 * authenticated 24h TTL, bounded AtomicFile, revocation and fail-closed behavior.
 * No background persistence; only the explicit UI save action writes.
 */
internal class PrivateRecipeDraftEncryptedEvidenceCheckpoint(
    context: Context,
    now: () -> Long = System::currentTimeMillis,
    keyProvider: () -> SecretKey = {
        PrivateRecipeDraftEncryptedImportCheckpoint.androidKey(
            "eg.recipe.unconfirmed.evidence.v1")
    }
) : RecipeDraftEncryptedEvidenceCheckpoint {
    private val encrypted = PrivateRecipeDraftEncryptedImportCheckpoint(
        context, now, keyProvider, "recipe-draft-encrypted-evidence-v1")

    override fun save(form: RecipeDraftEvidenceForm) {
        validate(form)
        val root = JSONObject()
            .put("v", 1)
            .put("draftHash", form.draftHash)
            .put("restrictions", form.restrictionsText)
        fun writeMap(name: String, values: Map<String, String>) {
            val obj = JSONObject()
            values.toSortedMap().forEach { (key, value) -> obj.put(key, value) }
            root.put(name, obj)
        }
        writeMap("versions", form.versionIds)
        writeMap("bases", form.bases)
        writeMap("states", form.states)
        val answers = JSONArray()
        form.allergens.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .forEach { (key, value) ->
                answers.put(JSONObject().put("food", key.first).put("allergen", key.second)
                    .put("answer", when (value) {
                        true -> "YES"
                        false -> "NO"
                        null -> "UNKNOWN"
                    }))
            }
        root.put("allergens", answers)
        encrypted.save(root.toString())
    }

    override fun load(): RecipeDraftEvidenceForm? {
        val raw = encrypted.load() ?: return null
        return runCatching {
            val root = JSONObject(raw)
            require(root.getInt("v") == 1)
            fun readMap(name: String): Map<String, String> {
                val obj = root.getJSONObject(name)
                require(obj.length() <= 64)
                val result = linkedMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    result[key] = obj.getString(key)
                }
                return result
            }
            val array = root.getJSONArray("allergens")
            require(array.length() <= 256)
            val answers = linkedMapOf<Pair<String, String>, Boolean?>()
            for (index in 0 until array.length()) {
                val row = array.getJSONObject(index)
                val key = row.getString("food") to row.getString("allergen")
                require(!answers.containsKey(key))
                answers[key] = when (row.getString("answer")) {
                    "YES" -> true
                    "NO" -> false
                    "UNKNOWN" -> null
                    else -> error("invalid answer")
                }
            }
            RecipeDraftEvidenceForm(root.getString("draftHash"), readMap("versions"),
                readMap("bases"), readMap("states"), answers,
                root.getString("restrictions")).also(::validate)
        }.getOrNull()
    }

    override fun discard() = encrypted.discard()

    private fun validate(form: RecipeDraftEvidenceForm) {
        require(form.draftHash.matches(Regex("[0-9a-f]{64}")))
        require(form.restrictionsText.length <= 1024)
        val maps = listOf(form.versionIds, form.bases, form.states)
        require(maps.all { it.size <= 64 })
        fun validId(value: String) = value.isNotBlank() && value.length <= 128
        require(maps.all { map ->
            map.all { (key, value) -> validId(key) && value.length <= 128 }
        })
        require(form.bases.values.all { it in setOf("NET", "DRAINED", "GROSS", "PER_PORTION", "PIECE", "UNKNOWN") })
        require(form.states.values.all { it in setOf("RAW", "COOKED") })
        require(form.allergens.size <= 256)
        require(form.allergens.keys.all { validId(it.first) && validId(it.second) })
    }
}
