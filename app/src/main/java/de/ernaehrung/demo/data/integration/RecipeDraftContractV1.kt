package de.ernaehrung.demo.data.integration

import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate

/** Offline runtime assertions for the exact pinned v1 schema, including its
 * optional fields and nested definitions. Supports every validation keyword in
 * that artifact; rejects unsupported schema keywords instead of ignoring them.
 * This is not an arbitrary-schema service and has no remote reference resolver.
 * Schema validity never supplies catalog evidence, consent or booking authority.
 */
internal object RecipeDraftContractV1 {
    const val SCHEMA_RESOURCE = "integration/recipe-contract-v1.json"
    const val SCHEMA_BLOB = "bedde4677342817fe6b883962c8dfbbd60100072"
    private val keywords = setOf("\$schema", "\$id", "title", "description", "\$defs",
        "\$ref", "type", "const", "enum", "allOf", "anyOf", "if", "then",
        "required", "properties", "additionalProperties", "items", "minItems",
        "uniqueItems", "minLength", "pattern", "minimum", "maximum", "format")

    private val root by lazy {
        val bytes = requireNotNull(RecipeDraftContractV1::class.java.classLoader
            ?.getResourceAsStream(SCHEMA_RESOURCE)) { "Pinned v1 contract unavailable" }.use { it.readBytes() }
        val header = "blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1").run { update(header); digest(bytes) }
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(digest == SCHEMA_BLOB) { "Pinned v1 contract changed without a version decision" }
        RecipeDraftBoundedJson.decode(bytes.toString(Charsets.UTF_8)).also(::checkSchema)
    }

    /** Keep separate from the importer's additional semantic/freshness limits. */
    fun requireValid(message: Map<String, Any?>) {
        require(message["messageType"] == "recipe.draft" && matches(root, message, 0)) {
            "recipe.draft violates the pinned 1.0.0-draft schema"
        }
    }

    private fun checkSchema(node: Any?) {
        if (node is Boolean) return
        val schema = requireNotNull(node as? Map<*, *>) { "Invalid pinned schema node" }
        require(schema.keys.all { it in keywords }) { "Unsupported pinned schema keyword" }
        schema["\$ref"]?.let { require(it is String && it.startsWith("#/")) { "Remote schema reference denied" } }
        for (key in listOf("properties", "\$defs")) {
            (schema[key] as? Map<*, *>)?.values?.forEach(::checkSchema)
        }
        for (key in listOf("allOf", "anyOf")) {
            (schema[key] as? List<*>)?.forEach(::checkSchema)
        }
        for (key in listOf("if", "then", "items", "additionalProperties")) {
            if (schema.containsKey(key)) checkSchema(schema[key])
        }
        schema["format"]?.let { require(it in setOf("date", "date-time", "uri")) }
        (schema["pattern"] as? String)?.let(::Regex)
    }

    private fun referenced(pointer: String): Any? {
        require(pointer.startsWith("#/"))
        var node: Any? = root
        for (part in pointer.removePrefix("#/").split('/')) {
            node = (node as? Map<*, *>)?.get(part.replace("~1", "/").replace("~0", "~"))
                ?: throw IllegalArgumentException("Missing pinned schema reference")
        }
        return node
    }

    private fun decimal(value: Any?): BigDecimal? =
        if (value is Number) value.toString().toBigDecimalOrNull() else null

    private fun typeMatches(type: String, value: Any?): Boolean = when (type) {
        "null" -> value == null
        "object" -> value is Map<*, *>
        "array" -> value is List<*>
        "string" -> value is String
        "boolean" -> value is Boolean
        "number" -> decimal(value) != null
        "integer" -> decimal(value)?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
        else -> throw IllegalArgumentException("Unsupported pinned schema type")
    }

    private fun jsonEqual(a: Any?, b: Any?): Boolean {
        if (a is Number && b is Number) return decimal(a)?.compareTo(decimal(b)) == 0
        if (a is Map<*, *> && b is Map<*, *>)
            return a.keys == b.keys && a.all { (k, v) -> jsonEqual(v, b[k]) }
        if (a is List<*> && b is List<*>)
            return a.size == b.size && a.indices.all { jsonEqual(a[it], b[it]) }
        return a == b
    }

    private fun matches(node: Any?, value: Any?, depth: Int): Boolean {
        require(depth <= 64) { "Pinned schema evaluation depth exceeded" }
        if (node is Boolean) return node
        val schema = node as Map<*, *>
        fun accepts(child: Any?, input: Any? = value) = matches(child, input, depth + 1)
        (schema["\$ref"] as? String)?.let { if (!accepts(referenced(it))) return false }
        val types = when (val type = schema["type"]) {
            is String -> listOf(type)
            is List<*> -> type.map { it as String }
            else -> null
        }
        if (types != null && types.none { typeMatches(it, value) }) return false
        if (schema.containsKey("const") && !jsonEqual(schema["const"], value)) return false
        (schema["enum"] as? List<*>)?.let { if (it.none { item -> jsonEqual(item, value) }) return false }
        (schema["allOf"] as? List<*>)?.let { if (!it.all { child -> accepts(child) }) return false }
        (schema["anyOf"] as? List<*>)?.let { if (it.none { child -> accepts(child) }) return false }
        if (schema.containsKey("if") && accepts(schema["if"]) &&
            schema.containsKey("then") && !accepts(schema["then"])) return false
        if (value is Map<*, *>) {
            (schema["required"] as? List<*>)?.let { if (!value.keys.containsAll(it)) return false }
            val properties = schema["properties"] as? Map<*, *> ?: emptyMap<Any, Any>()
            for ((key, child) in properties) {
                if (value.containsKey(key) && !accepts(child, value[key])) return false
            }
            if (schema.containsKey("additionalProperties")) {
                val extra = schema["additionalProperties"]
                if (value.any { (key, item) -> key !in properties && !accepts(extra, item) }) return false
            }
        }
        if (value is List<*>) {
            decimal(schema["minItems"])?.let { if (value.size.toBigDecimal() < it) return false }
            if (schema.containsKey("items") && !value.all { accepts(schema["items"], it) }) return false
            if (schema["uniqueItems"] == true) {
                for (i in value.indices) for (j in 0 until i) if (jsonEqual(value[i], value[j])) return false
            }
        }
        if (value is String) {
            decimal(schema["minLength"])?.let {
                if (value.codePointCount(0, value.length).toBigDecimal() < it) return false
            }
            (schema["pattern"] as? String)?.let { if (!Regex(it).containsMatchIn(value)) return false }
            (schema["format"] as? String)?.let { if (!formatMatches(it, value)) return false }
        }
        decimal(value)?.let { number ->
            decimal(schema["minimum"])?.let { if (number < it) return false }
            decimal(schema["maximum"])?.let { if (number > it) return false }
        }
        return true
    }

    private fun formatMatches(format: String, text: String): Boolean = runCatching {
        when (format) {
            "date" -> Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text) &&
                LocalDate.parse(text).year in 1..9999
            // RFC3339 permits arbitrary fractional precision and numeric offsets
            // through 23:59. Host time/freshness rules are deliberately separate.
            "date-time" -> {
                val match = Regex("([0-9]{4}-[0-9]{2}-[0-9]{2})[Tt]([0-9]{2}):([0-9]{2}):([0-9]{2})(\\.[0-9]+)?([Zz]|[+-]([0-9]{2}):([0-9]{2}))")
                    .matchEntire(text)
                match != null && LocalDate.parse(match.groupValues[1]).year in 1..9999 &&
                    match.groupValues[2].toInt() in 0..23 &&
                    match.groupValues[3].toInt() in 0..59 &&
                    match.groupValues[4].toInt() in 0..59 &&
                    (match.groupValues[6].uppercase() == "Z" ||
                        (match.groupValues[7].toInt() in 0..23 && match.groupValues[8].toInt() in 0..59))
            }
            "uri" -> text.all { it.code in 33..126 } && URI(text).isAbsolute
            else -> false
        }
    }.getOrDefault(false)
}
