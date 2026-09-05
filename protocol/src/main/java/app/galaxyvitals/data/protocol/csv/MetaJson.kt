package app.galaxyvitals.data.protocol.csv

import app.galaxyvitals.data.protocol.EcgParseException

/** Strict, dependency-free JSON object reader so the parser also runs in JVM unit tests. */
internal class MetaJson(raw: String) {
    private val values: Map<String, JsonValue> = JsonParser(raw).parse()

    init {
        rejectAliasPair("signFactor", "sign_factor")
        rejectAliasPair("polarityNormalized", "polarity_normalized")
    }

    fun has(key: String): Boolean = values.containsKey(key)

    fun string(key: String, default: String): String = when (val value = values[key]) {
        null -> default
        is JsonValue.StringValue -> value.value
        else -> throw EcgParseException("Invalid $key metadata")
    }

    fun nullableString(key: String): String? = when (val value = values[key]) {
        null, JsonValue.NullValue -> null
        is JsonValue.StringValue -> value.value
        else -> throw EcgParseException("Invalid $key metadata")
    }

    fun int(key: String, default: Int): Int = when (val value = values[key]) {
        null -> default
        is JsonValue.NumberValue -> value.token.toIntOrNull()
            ?: throw EcgParseException("Invalid $key metadata")
        else -> throw EcgParseException("Invalid $key metadata")
    }

    fun long(key: String, default: Long): Long = when (val value = values[key]) {
        null -> default
        is JsonValue.NumberValue -> value.token.toLongOrNull()
            ?: throw EcgParseException("Invalid $key metadata")
        else -> throw EcgParseException("Invalid $key metadata")
    }

    fun nullableLong(key: String): Long? = when (val value = values[key]) {
        null, JsonValue.NullValue -> null
        is JsonValue.NumberValue -> value.token.toLongOrNull()
            ?: throw EcgParseException("Invalid $key metadata")
        else -> throw EcgParseException("Invalid $key metadata")
    }

    fun nullableInt(key: String): Int? = when (val value = values[key]) {
        null, JsonValue.NullValue -> null
        is JsonValue.NumberValue -> value.token.toIntOrNull()
            ?: throw EcgParseException("Invalid $key metadata")
        else -> throw EcgParseException("Invalid $key metadata")
    }

    fun intList(key: String): List<Int> = when (val value = values[key]) {
        null, JsonValue.NullValue -> emptyList()
        is JsonValue.ArrayValue -> value.values.map { item ->
            (item as? JsonValue.NumberValue)?.token?.toIntOrNull()
                ?: throw EcgParseException("Invalid $key metadata")
        }
        else -> throw EcgParseException("Invalid $key metadata")
    }

    fun double(key: String, default: Double): Double = when (val value = values[key]) {
        null -> default
        is JsonValue.NumberValue -> value.token.toDoubleOrNull()
            ?: throw EcgParseException("Invalid $key metadata")
        else -> throw EcgParseException("Invalid $key metadata")
    }

    fun nullableDouble(key: String): Double? = when (val value = values[key]) {
        null, JsonValue.NullValue -> null
        is JsonValue.NumberValue -> value.token.toDoubleOrNull()
            ?.takeIf(Double::isFinite)
            ?: throw EcgParseException("Invalid $key metadata")
        else -> throw EcgParseException("Invalid $key metadata")
    }

    fun bool(key: String, default: Boolean): Boolean = when (val value = values[key]) {
        null -> default
        is JsonValue.BooleanValue -> value.value
        else -> throw EcgParseException("Invalid $key metadata")
    }

    private fun rejectAliasPair(first: String, second: String) {
        if (values.containsKey(first) && values.containsKey(second)) {
            throw EcgParseException("Duplicate reserved metadata keys: $first and $second")
        }
    }

    private sealed interface JsonValue {
        data class StringValue(val value: String) : JsonValue
        data class NumberValue(val token: String) : JsonValue
        data class BooleanValue(val value: Boolean) : JsonValue
        data class ArrayValue(val values: List<JsonValue>) : JsonValue
        data object NullValue : JsonValue
        data object ContainerValue : JsonValue
    }

    private class JsonParser(private val source: String) {
        private var index = 0

        fun parse(): Map<String, JsonValue> {
            if (source.length > MAX_LINE_CHARS) fail("metadata is too long")
            skipWhitespace()
            val result = parseObject(depth = 0)
            skipWhitespace()
            if (index != source.length) fail("unexpected trailing content")
            return result
        }

        private fun parseObject(depth: Int): Map<String, JsonValue> {
            checkDepth(depth)
            expect('{')
            skipWhitespace()
            val result = LinkedHashMap<String, JsonValue>()
            if (consume('}')) return result
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("object key must be a string")
                val key = parseString()
                if (result.containsKey(key)) {
                    throw EcgParseException("Duplicate metadata key: $key")
                }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = parseValue(depth + 1)
                skipWhitespace()
                when {
                    consume('}') -> return result
                    consume(',') -> Unit
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): List<JsonValue> {
            checkDepth(depth)
            expect('[')
            skipWhitespace()
            val result = ArrayList<JsonValue>()
            if (consume(']')) return result
            while (true) {
                result += parseValue(depth + 1)
                skipWhitespace()
                when {
                    consume(']') -> return result
                    consume(',') -> {
                        skipWhitespace()
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseValue(depth: Int): JsonValue {
            checkDepth(depth)
            return when (peek()) {
                '"' -> JsonValue.StringValue(parseString())
                '{' -> {
                    parseObject(depth)
                    JsonValue.ContainerValue
                }
                '[' -> {
                    JsonValue.ArrayValue(parseArray(depth))
                }
                't' -> {
                    expectLiteral("true")
                    JsonValue.BooleanValue(true)
                }
                'f' -> {
                    expectLiteral("false")
                    JsonValue.BooleanValue(false)
                }
                'n' -> {
                    expectLiteral("null")
                    JsonValue.NullValue
                }
                '-', in '0'..'9' -> JsonValue.NumberValue(parseNumber())
                else -> fail("invalid JSON value")
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                when {
                    char == '"' -> return result.toString()
                    char == '\\' -> {
                        if (index >= source.length) fail("unterminated string escape")
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> result.append(parseUnicodeEscape())
                            else -> fail("invalid string escape")
                        }
                    }
                    char.code < 0x20 -> fail("unescaped control character")
                    else -> result.append(char)
                }
            }
            fail("unterminated string")
        }

        private fun parseUnicodeEscape(): Char {
            if (index + 4 > source.length) fail("incomplete unicode escape")
            val token = source.substring(index, index + 4)
            val codePoint = token.toIntOrNull(16) ?: fail("invalid unicode escape")
            index += 4
            return codePoint.toChar()
        }

        private fun parseNumber(): String {
            val start = index
            consume('-')
            when (peek()) {
                '0' -> {
                    index++
                    if (peek() in '0'..'9') fail("number has a leading zero")
                }
                in '1'..'9' -> while (peek() in '0'..'9') index++
                else -> fail("invalid number")
            }
            if (consume('.')) {
                if (peek() !in '0'..'9') fail("invalid number fraction")
                while (peek() in '0'..'9') index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                if (peek() !in '0'..'9') fail("invalid number exponent")
                while (peek() in '0'..'9') index++
            }
            return source.substring(start, index)
        }

        private fun expectLiteral(literal: String) {
            if (!source.regionMatches(index, literal, 0, literal.length)) {
                fail("invalid literal")
            }
            index += literal.length
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) fail("expected '$expected'")
        }

        private fun consume(expected: Char): Boolean {
            if (peek() != expected) return false
            index++
            return true
        }

        private fun peek(): Char = source.getOrNull(index) ?: '\u0000'

        private fun skipWhitespace() {
            while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') {
                index++
            }
        }

        private fun checkDepth(depth: Int) {
            if (depth > MAX_JSON_DEPTH) fail("metadata nesting is too deep")
        }

        private fun fail(message: String): Nothing {
            throw EcgParseException("Malformed ECG metadata: $message at character $index")
        }
    }
}
