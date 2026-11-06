package dev.harness.core

import dev.harness.tools.Workspace
import java.io.File

/**
 * Lightweight codebase index: file list with language and size, plus top-level symbols found
 * with per-language regexes. Output is kept small enough to sit in the environment block.
 */
class RepoMapper(
    private val maxFiles: Int = 300,
    private val maxSymbolsPerFile: Int = 12,
    private val maxChars: Int = 6000,
) {
    data class Entry(val path: String, val language: String, val size: Long, val symbols: List<String>)

    fun scan(root: File): List<Entry> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { it == root || (it.name !in Workspace.SKIP_DIRS && !it.name.startsWith(".")) }
            .filter { it.isFile && !it.name.startsWith(".") }
            .take(maxFiles)
            .map { f ->
                val lang = language(f.name)
                val symbols = if (lang != null && f.length() < 500_000) symbols(f, lang) else emptyList()
                Entry(f.relativeTo(root).invariantSeparatorsPath, lang ?: "other", f.length(), symbols)
            }
            .sortedBy { it.path }
            .toList()
    }

    fun render(root: File): String {
        val entries = scan(root)
        if (entries.isEmpty()) return "(workspace is empty)"
        val sb = StringBuilder()
        for (e in entries) {
            val line = buildString {
                append(e.path).append(" [").append(e.language).append(", ").append(humanSize(e.size)).append(']')
                if (e.symbols.isNotEmpty()) append(": ").append(e.symbols.joinToString(", "))
            }
            if (sb.length + line.length + 1 > maxChars) {
                sb.append("… (${entries.size} files total; map truncated)")
                break
            }
            sb.appendLine(line)
        }
        return sb.toString().trimEnd()
    }

    private fun symbols(f: File, lang: String): List<String> {
        val patterns = PATTERNS[lang] ?: return emptyList()
        val out = LinkedHashSet<String>()
        runCatching {
            f.useLines { lines ->
                for (line in lines) {
                    for (p in patterns) {
                        p.find(line)?.let { m -> out += m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: m.value.trim() }
                    }
                    if (out.size >= maxSymbolsPerFile) break
                }
            }
        }
        return out.take(maxSymbolsPerFile)
    }

    companion object {
        private val EXT = mapOf(
            "kt" to "kotlin", "kts" to "kotlin", "java" to "java", "py" to "python", "js" to "javascript",
            "mjs" to "javascript", "jsx" to "javascript", "ts" to "typescript", "tsx" to "typescript", "go" to "go",
            "rs" to "rust", "c" to "c", "h" to "c", "cpp" to "cpp", "cc" to "cpp", "hpp" to "cpp", "swift" to "swift",
            "rb" to "ruby", "php" to "php", "sh" to "shell", "md" to "markdown", "json" to "json", "yaml" to "yaml",
            "yml" to "yaml", "toml" to "toml", "html" to "html", "css" to "css", "sql" to "sql", "dart" to "dart",
            "lua" to "lua", "xml" to "xml",
        )

        fun language(name: String): String? = EXT[name.substringAfterLast('.', "").lowercase()]

        private val PATTERNS: Map<String, List<Regex>> = mapOf(
            "kotlin" to listOf(
                Regex("^\\s*(?:(?:public|private|internal|protected|abstract|open|data|sealed|enum|inner|value|annotation)\\s+)*(?:class|interface|object)\\s+(\\w+)"),
                Regex("^\\s*(?:(?:public|private|internal|protected|override|suspend|inline|operator|infix|tailrec)\\s+)*fun\\s+(?:<[^>]+>\\s*)?(?:[\\w.]+\\.)?(\\w+)\\s*\\("),
            ),
            "java" to listOf(
                Regex("^\\s*(?:public|private|protected|abstract|final|static|\\s)*(?:class|interface|enum|record)\\s+(\\w+)"),
                Regex("^\\s*(?:public|private|protected)\\s+(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{?"),
            ),
            "python" to listOf(Regex("^(?:class|def|async def)\\s+(\\w+)")),
            "javascript" to listOf(
                Regex("^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s*\\*?\\s*(\\w+)"),
                Regex("^\\s*(?:export\\s+)?class\\s+(\\w+)"),
                Regex("^\\s*(?:export\\s+)?const\\s+(\\w+)\\s*=\\s*(?:async\\s*)?\\("),
            ),
            "typescript" to listOf(
                Regex("^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+(\\w+)"),
                Regex("^\\s*(?:export\\s+)?(?:abstract\\s+)?(?:class|interface|type|enum)\\s+(\\w+)"),
                Regex("^\\s*(?:export\\s+)?const\\s+(\\w+)\\s*=\\s*(?:async\\s*)?\\("),
            ),
            "go" to listOf(Regex("^func\\s+(?:\\([^)]*\\)\\s*)?(\\w+)"), Regex("^type\\s+(\\w+)")),
            "rust" to listOf(Regex("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?(?:fn|struct|enum|trait|mod)\\s+(\\w+)")),
            "c" to listOf(Regex("^[\\w\\s\\*]+?\\b(\\w+)\\s*\\([^;]*\\)\\s*\\{?\\s*$")),
            "cpp" to listOf(Regex("^\\s*(?:class|struct|namespace)\\s+(\\w+)"), Regex("^[\\w:<>\\s\\*&]+?\\b(\\w+)\\s*\\([^;]*\\)\\s*(?:const)?\\s*\\{?\\s*$")),
            "swift" to listOf(Regex("^\\s*(?:public\\s+|private\\s+|internal\\s+|open\\s+|final\\s+)*(?:class|struct|enum|protocol|func|extension)\\s+(\\w+)")),
            "ruby" to listOf(Regex("^\\s*(?:class|module|def)\\s+([\\w.?!]+)")),
            "php" to listOf(Regex("^\\s*(?:abstract\\s+|final\\s+)?(?:class|interface|trait|function)\\s+(\\w+)")),
            "dart" to listOf(Regex("^\\s*(?:abstract\\s+)?class\\s+(\\w+)")),
            "shell" to listOf(Regex("^\\s*(?:function\\s+)?(\\w+)\\s*\\(\\)\\s*\\{")),
            "markdown" to listOf(Regex("^#{1,2}\\s+(.+)$")),
        )

        fun humanSize(b: Long): String = when {
            b < 1024 -> "${b}B"
            b < 1024 * 1024 -> "%.1fKB".format(b / 1024.0)
            else -> "%.1fMB".format(b / 1_048_576.0)
        }
    }
}
