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
    val restrictionsText: String,
    val diets: Map<Pair<String, String>, Boolean?> = emptyMap()
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
            // Version 2 adds unconfirmed dietary answers; v1 remains readable.
            .put("v", 2)
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
        fun writeAnswers(name: String, token: String, values: Map<Pair<String, String>, Boolean?>) {
            val answers = JSONArray()
            values.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
                .forEach { (key, value) ->
                    answers.put(JSONObject().put("food", key.first).put(token, key.second)
                        .put("answer", when (value) {
                            true -> "YES"
                            false -> "NO"
                            null -> "UNKNOWN"
                        }))
                }
            root.put(name, answers)
        }
        writeAnswers("allergens", "allergen", form.allergens)
        writeAnswers("diets", "diet", form.diets)
        encrypted.save(root.toString())
    }

    override fun load(): RecipeDraftEvidenceForm? {
        val raw = encrypted.load() ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val version = root.getInt("v")
            require(version in 1..2)
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
            fun readAnswers(name: String, token: String): Map<Pair<String, String>, Boolean?> {
                val array = root.getJSONArray(name)
                require(array.length() <= 256)
                val answers = linkedMapOf<Pair<String, String>, Boolean?>()
                for (index in 0 until array.length()) {
                    val row = array.getJSONObject(index)
                    val key = row.getString("food") to row.getString(token)
                    require(!answers.containsKey(key))
                    answers[key] = when (row.getString("answer")) {
                        "YES" -> true
                        "NO" -> false
                        "UNKNOWN" -> null
                        else -> error("invalid answer")
                    }
                }
                return answers
            }
            RecipeDraftEvidenceForm(root.getString("draftHash"), readMap("versions"),
                readMap("bases"), readMap("states"), readAnswers("allergens", "allergen"),
                root.getString("restrictions"),
                if (version == 1) emptyMap() else readAnswers("diets", "diet")).also(::validate)
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
        require(form.diets.size <= 256)
        require(form.diets.keys.all { validId(it.first) && validId(it.second) &&
            it.second == it.second.uppercase() })
    }
}
