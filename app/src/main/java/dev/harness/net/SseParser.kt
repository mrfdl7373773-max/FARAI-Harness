package dev.harness.net

/**
 * Incremental Server-Sent Events parser. Feed it lines as they arrive; it returns the
 * data payload whenever an event is complete (blank line), joining multi-line data fields.
 */
class SseParser {
    private val data = StringBuilder()
    private var hasData = false

    /** Returns a complete event's data, or null if the event is not finished yet. */
    fun feed(rawLine: String): String? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null // comment / keep-alive
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        if (field == "data") {
            if (hasData) data.append('\n')
            data.append(value)
            hasData = true
        }
        return null
    }

    /** Emits any pending event (call at end of stream). */
    fun flush(): String? {
        if (!hasData) return null
        val out = data.toString()
        data.clear()
        hasData = false
        return out
    }

    companion object {
        const val DONE = "[DONE]"

        fun parseAll(text: String): List<String> {
            val p = SseParser()
            val out = mutableListOf<String>()
            text.split('\n').forEach { l -> p.feed(l)?.let { out += it } }
            p.flush()?.let { out += it }
            return out
        }
    }
}
