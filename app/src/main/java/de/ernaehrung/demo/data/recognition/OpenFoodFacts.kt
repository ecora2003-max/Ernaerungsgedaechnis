package de.ernaehrung.demo.data.recognition

import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class ExternalProductDraft(val barcode: String, val name: String?, val brand: String?, val suggestedUnit: BaseUnit?,
    val packageAmount: Long?, val quantityText: String?, val nutrients: Map<Nutrient, Double?>,
    val ingredients: String?, val allergens: String?, val sourceUrl: String, val fetchedAt: Long, val warnings: List<String>)
sealed interface ProductLookup {
    data class Found(val draft: ExternalProductDraft) : ProductLookup
    data class NotFound(val barcode: String) : ProductLookup
}
fun interface ProductLookupGateway { suspend fun lookup(barcode: String): ProductLookup }

/** One explicit public-data request. No image, receipt, local catalogue or personal data leaves the device. */
class OpenFoodFactsClient(private val now: () -> Long = System::currentTimeMillis) : ProductLookupGateway {
    override suspend fun lookup(barcode: String): ProductLookup = withContext(Dispatchers.IO) {
        val code = Gtin.parse(barcode).printed
        val connection = URL(apiUrl(code)).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Ernaehrungsgedaechtnis/0.9 (https://github.com/ecora2003-max/Ernaerungsgedaechnis)")
            when (val status = connection.responseCode) {
                404 -> ProductLookup.NotFound(code)
                200 -> {
                    val body = connection.inputStream.use { stream ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer); if (n < 0) break
                            require(out.size() + n <= MAX_RESPONSE) { "Produktantwort ist zu groß." }
                            out.write(buffer, 0, n)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                    parse(code, body, now())
                }
                429 -> throw IllegalStateException("Open Food Facts ist gerade ausgelastet. Später erneut versuchen oder manuell erfassen.")
                else -> throw IllegalStateException("Produktabfrage fehlgeschlagen (HTTP $status). Offline-Katalog und manuelle Erfassung bleiben verfügbar.")
            }
        } finally { connection.disconnect() }
    }

    companion object {
        const val MAX_RESPONSE = 512_000
        fun apiUrl(code: String) = "https://world.openfoodfacts.org/api/v2/product/${Gtin.parse(code).printed}.json?fields=code,product_name,product_name_de,brands,quantity,product_quantity,product_quantity_unit,nutrition_data_per,nutriments,ingredients_text,ingredients_text_de,allergens,allergens_tags"
        fun productUrl(code: String) = "https://world.openfoodfacts.org/product/${Gtin.parse(code).printed}"
        fun parse(barcode: String, body: String, fetchedAt: Long): ProductLookup {
            require(body.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE) { "Produktantwort ist zu groß." }
            val requested = Gtin.parse(barcode); val json = JSONObject(body)
            if (json.optInt("status", -1) == 0) return ProductLookup.NotFound(requested.printed)
            require(json.optInt("status", -1) == 1) { "Produktantwort besitzt keinen gültigen Status." }
            val product = requireNotNull(json.optJSONObject("product")) { "Produktdaten fehlen in der Antwort." }
            val returned = product.text("code") ?: json.text("code")
            require(returned != null && Gtin.parse(returned).canonical == requested.canonical) { "Antwort gehört zu einem anderen Barcode." }
            val warnings = mutableListOf("Open Food Facts ist eine externe, gemeinschaftlich gepflegte Quelle. Produktvariante und Etikett prüfen.",
                "Nährwerte beziehen sich auf 100 g beziehungsweise 100 ml laut Quelle. Einheit und Bezugsmenge vor dem Speichern bestätigen.")
            val unit = when (product.text("product_quantity_unit")) { "g" -> BaseUnit.GRAM; "ml" -> BaseUnit.MILLILITRE; else -> null }
            val quantity = product.text("product_quantity")?.let { raw ->
                runCatching { BigDecimal(raw).multiply(BigDecimal(1000)).longValueExact().let(Amounts::positive) }.getOrNull()
            }.takeIf { unit != null }
            if (unit == null) warnings += "Basiseinheit nicht eindeutig aus den Produktdaten ableitbar."
            val nutriments = product.optJSONObject("nutriments") ?: JSONObject()
            val keys = mapOf(Nutrient.ENERGY to "energy-kcal", Nutrient.PROTEIN to "proteins", Nutrient.FAT to "fat", Nutrient.CARBS to "carbohydrates",
                Nutrient.FIBRE to "fiber", Nutrient.SUGAR to "sugars", Nutrient.SATURATED to "saturated-fat", Nutrient.SALT to "salt",
                Nutrient.CALCIUM to "calcium", Nutrient.IRON to "iron", Nutrient.MAGNESIUM to "magnesium", Nutrient.IODINE to "iodine",
                Nutrient.B12 to "vitamin-b12", Nutrient.VITAMIN_D to "vitamin-d")
            val nutrients = Nutrient.entries.associateWith { nutrient ->
                val raw = nutriments.text("${keys.getValue(nutrient)}_100g")
                if (raw == null) null else {
                    // OFF standardizes mineral/vitamin _100g values to grams, irrespective of the label input unit.
                    val factor = when (nutrient) {
                        Nutrient.CALCIUM, Nutrient.IRON, Nutrient.MAGNESIUM -> BigDecimal(1000)
                        Nutrient.IODINE, Nutrient.B12, Nutrient.VITAMIN_D -> BigDecimal(1_000_000)
                        else -> BigDecimal.ONE
                    }
                    runCatching { BigDecimal(raw).multiply(factor).toDouble().also { require(it.isFinite() && it >= 0) } }
                        .getOrElse { warnings += "Ungültige Quellenangabe für ${nutrient.label}; bleibt unbekannt."; null }
                }
            }
            val allergens = product.text("allergens") ?: product.optJSONArray("allergens_tags")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.joinToString(", ").takeIf(String::isNotBlank)
            }
            return ProductLookup.Found(ExternalProductDraft(requested.printed, product.text("product_name_de") ?: product.text("product_name"),
                product.text("brands"), unit, quantity, product.text("quantity"), nutrients,
                product.text("ingredients_text_de") ?: product.text("ingredients_text"), allergens, productUrl(requested.printed), fetchedAt, warnings))
        }
        private fun JSONObject.text(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).trim().takeIf(String::isNotEmpty)
        fun sourceDetail(draft: ExternalProductDraft): String = "Open Food Facts · ${draft.sourceUrl} · Abruf ${Instant.ofEpochMilli(draft.fetchedAt)} · Variante/Basis manuell geprüft; bearbeitbare Quellenwerte"
    }
}
