package dev.harness.tools

/** Converts glob patterns (with **, *, ?, {a,b}, [..]) to regexes over '/'-separated paths. */
object Glob {
    fun toRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        var inGroup = 0
        while (i < glob.length) {
            val c = glob[i]
            when (c) {
                '*' -> {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        val slashAfter = i + 2 < glob.length && glob[i + 2] == '/'
                        sb.append(if (slashAfter) "(?:.*/)?" else ".*")
                        i += if (slashAfter) 3 else 2
                        continue
                    }
                    sb.append("[^/]*")
                }
                '?' -> sb.append("[^/]")
                '{' -> { sb.append("(?:"); inGroup++ }
                '}' -> if (inGroup > 0) { sb.append(")"); inGroup-- } else sb.append("\\}")
                ',' -> sb.append(if (inGroup > 0) "|" else ",")
                '[' -> {
                    val end = glob.indexOf(']', i + 1)
                    if (end == -1) sb.append("\\[") else {
                        var cls = glob.substring(i + 1, end)
                        if (cls.startsWith("!")) cls = "^" + cls.substring(1)
                        sb.append('[').append(cls.replace("\\", "\\\\")).append(']')
                        i = end
                    }
                }
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append('$')
        return Regex(sb.toString())
    }

    /** Matches against the full relative path, and also the file name when the glob has no '/'. */
    fun matches(regex: Regex, relativePath: String, globHasSlash: Boolean): Boolean =
        regex.matches(relativePath) || (!globHasSlash && regex.matches(relativePath.substringAfterLast('/')))
}
