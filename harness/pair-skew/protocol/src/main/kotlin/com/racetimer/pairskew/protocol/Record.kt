package com.racetimer.pairskew.protocol

/**
 * The harness's one text format: every log line, every Data Layer payload, and what the analysis reads
 * back. One implementation serves the writer and the reader, so the two cannot drift apart.
 *
 * ```
 * v1 ROUND side=phone at=81234567890123 req=phone run=4411 id=17 prim=rpc near=1 ...
 * ```
 *
 * A version token, an upper-case kind, then `key=value` pairs separated by single spaces. A value may
 * hold no whitespace and no `=`. [format] refuses one rather than write a line that [parse] would split
 * differently; [safe] is how free text, such as an exception's name, gets into a value.
 */
data class Record(val kind: String, val fields: Map<String, String>) {

    init {
        require(KIND.matches(kind)) { "a kind is upper-case letters, was '$kind'" }
    }

    fun format(): String = buildString {
        append(VERSION).append(' ').append(kind)
        for ((key, value) in fields) {
            require(KEY.matches(key)) { "bad key '$key' in $kind" }
            require(value.isNotEmpty() && value.none { it.isWhitespace() || it == '=' }) {
                "bad value for '$key' in $kind: '$value'"
            }
            append(' ').append(key).append('=').append(value)
        }
    }

    operator fun get(key: String): String? = fields[key]

    fun text(key: String): String = fields[key] ?: throw MalformedRecord("$kind has no '$key'")

    fun long(key: String): Long =
        text(key).toLongOrNull() ?: throw MalformedRecord("$kind '$key' is not a whole number: ${fields[key]}")

    /** `1` and `0` as true and false; anything else, including `-` for "not known", as null. */
    fun flag(key: String): Boolean? = when (fields[key]) {
        "1" -> true
        "0" -> false
        else -> null
    }

    companion object {
        const val VERSION = "v1"
        private val KIND = Regex("[A-Z]+")
        private val KEY = Regex("[a-z][a-z0-9_]*")

        /**
         * The record on [line], or null if there is none. It is found wherever it starts, so a line
         * from the harness's own file and a logcat line behind its `I PairSkew:` prefix both parse.
         * A line that starts like a record and then breaks the format is refused whole: null, never a
         * record quietly missing the fields it lost.
         */
        fun parse(line: String): Record? {
            val start = locate(line) ?: return null
            val tokens = line.substring(start).trim().split(' ')
            if (tokens.size < 2 || tokens[0] != VERSION || !KIND.matches(tokens[1])) return null
            val fields = LinkedHashMap<String, String>()
            for (token in tokens.drop(2)) {
                val eq = token.indexOf('=')
                if (eq <= 0 || eq == token.length - 1) return null
                val key = token.substring(0, eq)
                val value = token.substring(eq + 1)
                if (!KEY.matches(key) || key in fields || '=' in value) return null
                fields[key] = value
            }
            return Record(tokens[1], fields)
        }

        /** Where a record starts on [line]: the version token at the start or after a space. */
        private fun locate(line: String): Int? {
            var from = 0
            while (true) {
                val i = line.indexOf("$VERSION ", from)
                if (i < 0) return null
                if (i == 0 || line[i - 1] == ' ') return i
                from = i + 1
            }
        }

        /** [text] made safe to carry as a value: whitespace and `=` become `_`, and nothing becomes `-`. */
        fun safe(text: String?): String {
            if (text.isNullOrEmpty()) return "-"
            return buildString(text.length) {
                for (c in text) append(if (c.isWhitespace() || c == '=') '_' else c)
            }
        }
    }
}

/** A record that parsed but lacks a field its kind requires, or carries one that does not read. */
class MalformedRecord(message: String) : RuntimeException(message)
