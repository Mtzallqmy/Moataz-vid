package com.moatazvid.ai.provider

/** Small strict JSON codec for provider envelopes. It has no reflection and rejects trailing input. */
object MiniJson {
    fun parse(text: String): JsonValue = Parser(text).parse()
    fun stringify(value: JsonValue): String = when (value) {
        is JsonValue.StringValue -> "\"${value.value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
        is JsonValue.NumberValue -> if (value.value % 1.0 == 0.0) value.value.toLong().toString() else value.value.toString()
        is JsonValue.BooleanValue -> value.value.toString()
        is JsonValue.ObjectValue -> value.value.entries.joinToString(",", "{", "}") { stringify(JsonValue.StringValue(it.key)) + ":" + stringify(it.value) }
        is JsonValue.ArrayValue -> value.value.joinToString(",", "[", "]", transform = ::stringify)
        JsonValue.NullValue -> "null"
    }
    fun obj(vararg values: Pair<String, JsonValue?>): JsonValue.ObjectValue = JsonValue.ObjectValue(values.mapNotNull { (k, v) -> v?.let { k to it } }.toMap())
    fun str(value: String?) = value?.let(JsonValue::StringValue)
    fun num(value: Number?) = value?.let { JsonValue.NumberValue(it.toDouble()) }
    fun bool(value: Boolean?) = value?.let(JsonValue::BooleanValue)
    fun array(values: List<JsonValue>) = JsonValue.ArrayValue(values)

    private class Parser(private val input: String) {
        private var index = 0
        fun parse(): JsonValue { val value = value(); ws(); require(index == input.length) { "Trailing JSON" }; return value }
        private fun value(): JsonValue { ws(); require(index < input.length) { "Unexpected end" }; return when (input[index]) {
            '{' -> objectValue(); '[' -> arrayValue(); '"' -> JsonValue.StringValue(string())
            't' -> literal("true", JsonValue.BooleanValue(true)); 'f' -> literal("false", JsonValue.BooleanValue(false));
            'n' -> literal("null", JsonValue.NullValue); else -> number()
        } }
        private fun objectValue(): JsonValue { index++; ws(); val map = linkedMapOf<String, JsonValue>(); if (take('}')) return JsonValue.ObjectValue(map)
            while (true) { ws(); val key = string(); ws(); expect(':'); map[key] = value(); ws(); if (take('}')) break; expect(',') }; return JsonValue.ObjectValue(map) }
        private fun arrayValue(): JsonValue { index++; ws(); val list = mutableListOf<JsonValue>(); if (take(']')) return JsonValue.ArrayValue(list)
            while (true) { list += value(); ws(); if (take(']')) break; expect(',') }; return JsonValue.ArrayValue(list) }
        private fun string(): String { expect('"'); val out = StringBuilder(); while (index < input.length) { val c = input[index++]; if (c == '"') return out.toString(); if (c != '\\') out.append(c) else { require(index < input.length); when (val e = input[index++]) {
            '"','\\','/' -> out.append(e); 'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t');
            'u' -> { require(index + 4 <= input.length); out.append(input.substring(index, index + 4).toInt(16).toChar()); index += 4 }; else -> error("Invalid escape") } } }; error("Unterminated string") }
        private fun number(): JsonValue { val start = index; while (index < input.length && input[index] in "-+0123456789.eE") index++; require(index > start); return JsonValue.NumberValue(input.substring(start, index).toDouble()) }
        private fun <T : JsonValue> literal(text: String, value: T): T { require(input.startsWith(text, index)); index += text.length; return value }
        private fun ws() { while (index < input.length && input[index].isWhitespace()) index++ }
        private fun expect(c: Char) { require(index < input.length && input[index] == c) { "Expected $c" }; index++ }
        private fun take(c: Char): Boolean = if (index < input.length && input[index] == c) { index++; true } else false
    }
}

fun JsonValue.objectOrNull() = (this as? JsonValue.ObjectValue)?.value
fun JsonValue.arrayOrNull() = (this as? JsonValue.ArrayValue)?.value
fun JsonValue.stringOrNull() = (this as? JsonValue.StringValue)?.value
fun JsonValue.numberOrNull() = (this as? JsonValue.NumberValue)?.value
fun JsonValue.booleanOrNull() = (this as? JsonValue.BooleanValue)?.value
