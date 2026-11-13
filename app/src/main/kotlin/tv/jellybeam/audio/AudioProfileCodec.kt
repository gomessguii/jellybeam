package tv.jellybeam.audio

import tv.jellybeam.player.AudioOutputKind

/**
 * JSON for [AudioProfilesState], hand-rolled because org.json is a stub in JVM tests. Unknown keys
 * and stage types are skipped, and a malformed file reads as empty rather than crashing the app.
 */
object AudioProfileCodec {
    private const val VERSION = 1

    private val outputKeys = mapOf(
        AudioOutputKind.HEADPHONES to "headphones",
        AudioOutputKind.SPEAKERS to "speakers",
        AudioOutputKind.EXTERNAL to "external",
    )

    fun encode(state: AudioProfilesState): String = buildString {
        append("{\"version\":").append(VERSION).append(",\"profiles\":[")
        state.profiles.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append("{\"id\":").append(quote(p.id))
            append(",\"name\":").append(quote(p.name))
            append(",\"gainDb\":").append(p.gainDb)
            append(",\"stages\":[")
            p.stages.forEachIndexed { j, s ->
                if (j > 0) append(',')
                when (s) {
                    is DspStage.Crossfeed -> append("{\"type\":\"crossfeed\",\"strength\":").append(s.strength)
                        .append(",\"range\":").append(s.range).append(",\"slope\":").append(s.slope).append('}')
                }
            }
            append("]}")
        }
        append("],\"headphoneVolume\":").append(state.headphoneVolume)
        append(",\"defaults\":{")
        state.defaults.entries.mapNotNull { (k, v) -> outputKeys[k]?.let { it to v } }.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append(quote(k)).append(':').append(quote(v))
        }
        append("}}")
    }

    fun decode(text: String): AudioProfilesState {
        val root = runCatching { JsonReader(text).readDocument() }.getOrNull() as? Map<*, *> ?: return AudioProfilesState()
        val profiles = (root["profiles"] as? List<*>).orEmpty().mapNotNull { decodeProfile(it as? Map<*, *>) }
        val ids = profiles.map { it.id }.toSet()
        val defaults = (root["defaults"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            val output = outputKeys.entries.firstOrNull { it.value == k }?.key
            if (output != null && v is String && v in ids) output to v else null
        }.toMap()
        val volume = (root["headphoneVolume"] as? Double)?.toInt()?.let(HeadphoneVolume::clamp) ?: HeadphoneVolume.MAX
        return AudioProfilesState(profiles, defaults, volume)
    }

    private fun decodeProfile(m: Map<*, *>?): AudioProfile? {
        m ?: return null
        val id = m["id"] as? String ?: return null
        val stages = (m["stages"] as? List<*>).orEmpty().mapNotNull { decodeStage(it as? Map<*, *>) }
        return AudioProfile(
            id = id,
            name = m["name"] as? String ?: id,
            gainDb = (m["gainDb"] as? Double ?: 0.0).coerceIn(DspLimits.GAIN_MIN_DB, DspLimits.GAIN_MAX_DB),
            stages = stages,
        )
    }

    private fun decodeStage(m: Map<*, *>?): DspStage? = when (m?.get("type")) {
        "crossfeed" -> {
            val d = DspStage.Crossfeed()
            DspStage.Crossfeed(
                strength = (m["strength"] as? Double ?: d.strength).coerceIn(0.0, 1.0),
                range = (m["range"] as? Double ?: d.range).coerceIn(0.0, 1.0),
                slope = (m["slope"] as? Double ?: d.slope).coerceIn(DspLimits.SLOPE_MIN, 1.0),
            )
        }
        else -> null
    }

    private fun quote(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}

/** Minimal JSON reader: objects, arrays, strings, numbers (as Double), booleans and null. */
private class JsonReader(private val s: String) {
    private var i = 0

    fun readDocument(): Any? = readValue().also {
        skipWs()
        require(i == s.length) { "trailing data" }
    }

    private fun readValue(): Any? {
        skipWs()
        require(i < s.length) { "unexpected end" }
        return when (val c = s[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) readNumber() else error("unexpected '$c'")
        }
    }

    private fun readObject(): Map<String, Any?> {
        i++
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (peek() == '}') return out.also { i++ }
        while (true) {
            skipWs()
            val key = readString()
            skipWs()
            expect(':')
            out[key] = readValue()
            skipWs()
            when (next()) {
                ',' -> continue
                '}' -> return out
                else -> error("expected , or }")
            }
        }
    }

    private fun readArray(): List<Any?> {
        i++
        val out = ArrayList<Any?>()
        skipWs()
        if (peek() == ']') return out.also { i++ }
        while (true) {
            out += readValue()
            skipWs()
            when (next()) {
                ',' -> continue
                ']' -> return out
                else -> error("expected , or ]")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            when (val c = next()) {
                '"' -> return sb.toString()
                '\\' -> when (val e = next()) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        require(i + 4 <= s.length) { "bad escape" }
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> error("bad escape")
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readNumber(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(start, i).toDouble()
    }

    private fun literal(word: String, value: Any?): Any? {
        require(s.startsWith(word, i)) { "bad literal" }
        i += word.length
        return value
    }

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun peek(): Char = s.getOrElse(i) { error("unexpected end") }

    private fun next(): Char = peek().also { i++ }

    private fun expect(c: Char) = require(next() == c) { "expected $c" }
}
