package dev.harness.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

class ReadTool : Tool {
    override val name = "read"
    override val readOnly = true
    override val parameters: JsonObject = schema {
        string("path", required = true)
        integer("offset")
        integer("limit")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val f = ctx.workspace.resolve(path)
        val offset = (args.int("offset") ?: 1).coerceAtLeast(1)
        val limit = (args.int("limit") ?: MAX_LINES).coerceIn(1, MAX_LINES)
        if (!f.exists()) {
            val alt = sameNameIgnoringUnicode(f)
            if (alt == null) return@withContext ToolResult.error(notFound(ctx, path, f))
            return@withContext readFile(ctx, alt, offset, limit)
        }
        FileOps.authorizeRead(ctx, "read", f)?.let { return@withContext ToolResult.error(it) }
        if (f.isDirectory) return@withContext listDir(ctx, f, offset, limit)
        readFile(ctx, f, offset, limit)
    }

    private fun readFile(ctx: ToolContext, f: File, offset: Int, limit: Int): ToolResult {
        val rel = ctx.workspace.relative(f)
        if (Workspace.isProbablyBinary(f)) {
            return ToolResult.error("$rel looks like a binary file (${f.length()} bytes). Inspect it with bash (file, xxd, unzip -l) instead.")
        }
        val lines = f.readLines()
        ctx.session.markSeen(f)
        if (lines.isEmpty()) return ToolResult("Read file $rel, 0 lines (empty file)" + instructions(ctx, f))
        if (offset > lines.size) return ToolResult.error("offset $offset is past the end of $rel (${lines.size} lines)")
        val sb = StringBuilder()
        var bytes = 0
        var last = offset - 1
        for (i in (offset - 1) until minOf(lines.size, offset - 1 + limit)) {
            val text = lines[i].let { if (it.length > MAX_LINE) it.take(MAX_LINE) + "… [line truncated, ${it.length} chars]" else it }
            val row = "${i + 1}: $text\n"
            if (bytes + row.length > MAX_BYTES && i > offset - 1) break
            sb.append(row); bytes += row.length; last = i + 1
        }
        val header = "Read file $rel, lines $offset-$last of ${lines.size}"
        val tail = if (last < lines.size) "\n[${lines.size - last} more lines. Continue with offset: ${last + 1}]" else ""
        return ToolResult(header + "\n" + sb.toString().trimEnd('\n') + tail + instructions(ctx, f))
    }

    private fun listDir(ctx: ToolContext, dir: File, offset: Int, limit: Int): ToolResult {
        val rel = ctx.workspace.relative(dir)
        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        val page = entries.drop(offset - 1).take(limit)
        val body = page.joinToString("\n") { e -> if (e.isDirectory) "${e.name}/" else "${e.name}  (${human(e.length())})" }
        val more = if (offset - 1 + page.size < entries.size) "\n[${entries.size - (offset - 1 + page.size)} more entries. Continue with offset: ${offset + page.size}]" else ""
        return ToolResult("Read directory $rel, ${entries.size} entries\n$body$more" + instructions(ctx, dir))
    }

    /**
     * AGENTS.md (or CLAUDE.md) in the folders between this path and the workspace root holds rules
     * for that part of the project. The root one is already in the system message; nested ones are
     * attached the first time the model reads something below them.
     */
    private fun instructions(ctx: ToolContext, target: File): String {
        val root = ctx.workspace.root
        if (!FileOps.isInsideWorkspace(ctx, target)) return ""
        var dir: File? = if (target.isDirectory) target else target.parentFile
        val found = ArrayList<File>()
        while (dir != null && dir.canonicalPath != root.canonicalPath && dir.canonicalPath.startsWith(root.canonicalPath)) {
            INSTRUCTION_FILES.map { File(dir, it) }.firstOrNull { it.isFile }?.let { found += it }
            dir = dir.parentFile
        }
        val fresh = found.filter { it.canonicalPath != target.canonicalPath && ctx.session.instructions.add(it.canonicalPath) }
        if (fresh.isEmpty()) return ""
        return fresh.reversed().joinToString("") { f ->
            "\n\n<system-reminder>\nInstructions from: ${ctx.workspace.relative(f)}\n${f.readText().take(12_000).trim()}\n</system-reminder>"
        }
    }

    private fun sameNameIgnoringUnicode(f: File): File? {
        val parent = f.parentFile ?: return null
        val want = TextMatch.normalizeChars(f.name).lowercase()
        return parent.listFiles()?.filter { TextMatch.normalizeChars(it.name).lowercase() == want }?.singleOrNull()
    }

    private fun notFound(ctx: ToolContext, input: String, f: File): String {
        var child = f
        var parent = f.parentFile
        while (parent != null && !parent.exists()) { child = parent; parent = parent.parentFile }
        if (parent == null) return "file not found: $input"
        val base = child.name.lowercase()
        val similar = parent.listFiles().orEmpty().map { it.name }.filter { n ->
            val c = n.lowercase(); c.contains(base) || base.contains(c) || levenshtein(c, base) <= 2
        }.take(4).map { ctx.workspace.relative(File(parent, it)) }
        val folderNote = if (child != f) "\nThe folder ${ctx.workspace.relative(child)} does not exist; the nearest existing one is ${ctx.workspace.relative(parent)}." else ""
        val did = if (similar.isNotEmpty()) "\nDid you mean: ${similar.joinToString(", ")}?" else ""
        return "file not found: $input$folderNote$did"
    }

    companion object {
        const val MAX_LINES = 2000
        const val MAX_LINE = 2000
        const val MAX_BYTES = 60_000
        val INSTRUCTION_FILES = listOf("AGENTS.md", "CLAUDE.md")

        fun human(n: Long): String = when {
            n < 1024 -> "$n B"
            n < 1024 * 1024 -> "${n / 1024} KB"
            else -> String.format("%.1f MB", n / 1048576.0)
        }

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]; dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}
