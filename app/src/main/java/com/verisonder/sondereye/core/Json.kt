package com.verisonder.sondereye.core

/**
 * Small JSON reader and string escaper. Pure Kotlin, so everything that parses feeds is
 * testable off-device. Objects become Map<String, Any?>, arrays List<Any?>, numbers Double.
 */
object Json {

    class ParseError(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val r = Reader(text)
        r.ws()
        val v = r.value()
        r.ws()
        if (r.i != text.length) throw ParseError("Extra content at ${r.i}")
        return v
    }

    /** Quoted, escaped JSON string. Also safe to paste into JavaScript source. */
    fun str(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                // U+2028/2029 are valid in JSON but end a line in older JavaScript engines.
                '\u2028' -> b.append("\\u2028")
                '\u2029' -> b.append("\\u2029")
                '<' -> b.append("\\u003c") // never closes a script tag
                else -> if (c < ' ') b.append("\\u%04x".format(c.code)) else b.append(c)
            }
        }
        return b.append('"').toString()
    }

    private class Reader(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw ParseError("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseError("Unexpected '$c' at $i")
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw ParseError("Bad literal at $i")
            i += w.length
            return v
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw ParseError("Bad number at $start")
        }

        fun string(): String {
            i++ // opening quote
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseError("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) throw ParseError("Bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseError("Bad unicode escape")
                                b.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw ParseError("Bad escape '\\$e'")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws(); out.add(value()); ws()
                if (i >= s.length) throw ParseError("Unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw ParseError("Expected , or ] at ${i - 1}")
                }
            }
        }

        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw ParseError("Expected key at $i")
                val k = string()
                ws()
                if (i >= s.length || s[i] != ':') throw ParseError("Expected : at $i")
                i++
                ws(); out[k] = value(); ws()
                if (i >= s.length) throw ParseError("Unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw ParseError("Expected , or } at ${i - 1}")
                }
            }
        }
    }
}
