package de.ernaehrung.demo.data.integration

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Dependency-free, read-only JSON boundary for recipe.draft imports.
 * Preserves exact JSON integer types; rejects duplicate keys and oversized input.
 * NOT a substitute for the pinned full JSON Schema 2020-12 validation in the host.
 */
internal object RecipeDraftBoundedJson {
    data class Limits(
        val maxChars: Int = 262_144,
        val maxDepth: Int = 32,
        val maxNodes: Int = 10_000,
        val maxStringChars: Int = 32_768
    ) {
        init {
            require(maxChars in 1..1_048_576)
            require(maxDepth in 1..128)
            require(maxNodes in 1..100_000)
            require(maxStringChars in 1..262_144)
        }
    }

    fun decode(text: String, limits: Limits = Limits()): Map<String, Any?> {
        require(text.length <= limits.maxChars) { "JSON exceeds character limit" }
        return Parser(text, limits).readRoot()
    }

    private class Parser(private val src: String, private val limits: Limits) {
        private var index = 0
        private var nodes = 0
        private val size get() = src.length
        private fun error(reason: String): Nothing = throw IllegalArgumentException("Invalid JSON at $index: $reason")
        private fun peek(): Char? = if (index < size) src[index] else null
        private fun ws() {
            while (index < size && when (src[index]) { ' ', '\t', '\r', '\n' -> true else -> false }) index++
        }
        private fun consume(c: Char) { if (peek() != c) error("expected '$c'"); index++ }
        private fun literal(word: String, value: Any?): Any? {
            if (!src.startsWith(word, index)) error("expected $word")
            index += word.length
            return value
        }
        fun readRoot(): Map<String, Any?> {
            ws()
            val result = value(0)
            ws()
            if (index != size) error("trailing characters")
            @Suppress("UNCHECKED_CAST")
            return result as? Map<String, Any?> ?: error("root must be a JSON object")
        }
        private fun value(depth: Int): Any? {
            if (depth > limits.maxDepth) error("nesting depth exceeded")
            if (++nodes > limits.maxNodes) error("node budget exceeded")
            ws()
            return when (peek()) {
                '{' -> obj(depth + 1)
                '[' -> array(depth + 1)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> number()
                else -> error("invalid value")
            }
        }
        private fun obj(depth: Int): Map<String, Any?> {
            consume('{'); ws()
            val map = linkedMapOf<String, Any?>()
            if (peek() == '}') { index++; return map }
            while (true) {
                if (peek() != '"') error("object key must be string")
                val key = string()
                if (map.containsKey(key)) error("duplicate key")
                ws(); consume(':')
                map[key] = value(depth)
                ws()
                when (peek()) {
                    '}' -> { index++; return map }
                    ',' -> { index++; ws() }
                    else -> error("expected comma or closing brace")
                }
            }
        }
        private fun array(depth: Int): List<Any?> {
            consume('['); ws()
            val arr = ArrayList<Any?>()
            if (peek() == ']') { index++; return arr }
            while (true) {
                arr.add(value(depth))
                ws()
                when (peek()) {
                    ']' -> { index++; return arr }
                    ',' -> index++
                    else -> error("expected comma or closing bracket")
                }
            }
        }
        private fun hex4(): Char {
            if (index + 4 > size) error("incomplete unicode escape")
            val code = src.substring(index, index + 4).toIntOrNull(16)
                ?: error("invalid unicode escape")
            index += 4
            return code.toChar()
        }
        private fun appendUnicode(out: StringBuilder, char: Char, escaped: Boolean) {
            if (char.isHighSurrogate()) {
                val low: Char = if (escaped) {
                    if (index + 6 > size || src[index] != '\\' || src[index + 1] != 'u') error("unpaired high surrogate")
                    index += 2; hex4()
                } else {
                    if (index >= size) error("unpaired high surrogate")
                    src[index++]
                }
                if (!low.isLowSurrogate()) error("unpaired high surrogate")
                out.append(char).append(low)
            } else if (char.isLowSurrogate()) error("unpaired low surrogate")
            else out.append(char)
            if (out.length > limits.maxStringChars) error("string limit exceeded")
        }
        private fun string(): String {
            consume('"')
            val out = StringBuilder()
            while (true) {
                val c = peek() ?: error("unterminated string")
                index++
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        val esc = peek() ?: error("unfinished escape")
                        index++
                        when (esc) {
                            '"', '\\', '/' -> out.append(esc)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> appendUnicode(out, hex4(), true)
                            else -> error("invalid string escape")
                        }
                    }
                    else -> {
                        if (c.code < 32) error("unescaped control character")
                        appendUnicode(out, c, false)
                    }
                }
                if (out.length > limits.maxStringChars) error("string limit exceeded")
            }
        }
        private fun number(): Any {
            val start = index
            if (peek() == '-') index++
            when (peek()) {
                '0' -> index++
                in '1'..'9' -> while (peek() in '0'..'9') index++
                else -> error("expected digit")
            }
            if (peek() == '.') {
                index++
                if (peek() !in '0'..'9') error("missing decimal digit")
                while (peek() in '0'..'9') index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                if (peek() !in '0'..'9') error("missing exponent digit")
                while (peek() in '0'..'9') index++
            }
            val raw = src.substring(start, index)
            if (raw.length > 128) error("number token too long")
            return if (raw.contains('.') || raw.contains('e', true)) {
                val n = BigDecimal(raw)
                if (n.precision() > 64 || n.scale() < -64 || n.scale() > 64) error("decimal precision/range exceeded")
                n
            } else {
                val n = BigInteger(raw)
                try { n.intValueExact() } catch (_: ArithmeticException) {
                    try { n.longValueExact() } catch (_: ArithmeticException) { n }
                }
            }
        }
    }
}
