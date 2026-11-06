package dev.harness.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Applies a multi-file patch in the `*** Begin Patch` format that GPT-family models are trained
 * on (Add / Update / Delete / Move). Every file is checked first and nothing is written unless
 * every hunk applies, so a bad patch never leaves the project half-changed.
 */
class PatchTool : Tool {
    override val name = "patch"
    override val parameters: JsonObject = schema {
        string("patch", required = true)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val ops = try { Patch.parse(args.reqStr("patch")) } catch (e: PatchException) {
            return@withContext ToolResult.error("could not parse the patch: ${e.message}")
        }
        if (ops.isEmpty()) return@withContext ToolResult.error("the patch has no file sections (*** Add File / *** Update File / *** Delete File)")

        data class Planned(val file: File, val before: String?, val after: String?, val label: String)
        val planned = ArrayList<Planned>()
        for (op in ops) {
            try {
                when (op) {
                    is Patch.Op.Add -> {
                        val f = ctx.workspace.resolve(op.path)
                        if (f.exists() && !ctx.session.hasSeen(f)) throw PatchException("${op.path} already exists; use *** Update File, or read it first if you mean to replace it")
                        planned += Planned(f, f.takeIf { it.isFile }?.readText(), op.content, "A ${ctx.workspace.relative(f)}")
                    }
                    is Patch.Op.Delete -> {
                        val f = ctx.workspace.resolve(op.path)
                        if (!f.isFile) throw PatchException("cannot delete ${op.path}: no such file")
                        planned += Planned(f, f.readText(), null, "D ${ctx.workspace.relative(f)}")
                    }
                    is Patch.Op.Update -> {
                        val f = ctx.workspace.resolve(op.path)
                        if (!f.isFile) throw PatchException("cannot update ${op.path}: no such file")
                        val before = f.readText()
                        val after = Patch.apply(before, op.hunks, op.path)
                        if (op.moveTo != null) {
                            val dest = ctx.workspace.resolve(op.moveTo)
                            planned += Planned(f, before, null, "R ${ctx.workspace.relative(f)} → ${ctx.workspace.relative(dest)}")
                            planned += Planned(dest, dest.takeIf { it.isFile }?.readText(), after, "")
                        } else planned += Planned(f, before, after, "M ${ctx.workspace.relative(f)}")
                    }
                }
            } catch (e: PatchException) {
                return@withContext ToolResult.error("patch not applied, nothing was changed: ${e.message}")
            }
        }

        val diffs = planned.joinToString("\n") { p -> FileOps.diff(ctx.workspace.relative(p.file), p.before.orEmpty(), p.after.orEmpty(), 80) }
        for (p in planned.distinctBy { it.file.canonicalPath }) {
            FileOps.authorizeEdit(ctx, p.file, diffs.take(6000))?.let { return@withContext ToolResult.error(it) }
        }
        for (p in planned) {
            ctx.beforeMutate(p.file)
            if (p.after == null) p.file.delete() else { FileOps.writeAtomic(p.file, p.after); ctx.session.markSeen(p.file) }
        }
        val summary = planned.filter { it.label.isNotEmpty() }.joinToString("\n") { it.label }
        ToolResult("patch applied (${planned.count { it.label.isNotEmpty() }} files, ${FileOps.stat(diffs)}):\n$summary\n\n$diffs")
    }
}

class PatchException(message: String) : Exception(message)

object Patch {
    sealed interface Op {
        data class Add(val path: String, val content: String) : Op
        data class Delete(val path: String) : Op
        data class Update(val path: String, val moveTo: String?, val hunks: List<Hunk>) : Op
    }

    data class Hunk(val header: String, val lines: List<Pair<Char, String>>, val endOfFile: Boolean = false)

    fun parse(text: String): List<Op> {
        val lines = text.replace("\r\n", "\n").trim('\n').lines().let { l ->
            // Models sometimes wrap the patch in a code fence.
            if (l.firstOrNull()?.startsWith("```") == true) l.drop(1).dropLastWhile { it.startsWith("```") || it.isBlank() } else l
        }
        var i = lines.indexOfFirst { it.trim() == "*** Begin Patch" }.let { if (it < 0) 0 else it + 1 }
        val ops = ArrayList<Op>()
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trim() == "*** End Patch" -> break
                line.startsWith("*** Add File:") -> {
                    val path = line.removePrefix("*** Add File:").trim()
                    i++
                    val body = ArrayList<String>()
                    while (i < lines.size && !lines[i].startsWith("*** ")) {
                        val l = lines[i]
                        body += if (l.startsWith("+")) l.substring(1) else l
                        i++
                    }
                    ops += Op.Add(path, body.joinToString("\n") + if (body.isNotEmpty()) "\n" else "")
                }
                line.startsWith("*** Delete File:") -> { ops += Op.Delete(line.removePrefix("*** Delete File:").trim()); i++ }
                line.startsWith("*** Update File:") -> {
                    val path = line.removePrefix("*** Update File:").trim()
                    i++
                    var moveTo: String? = null
                    if (i < lines.size && lines[i].startsWith("*** Move to:")) { moveTo = lines[i].removePrefix("*** Move to:").trim(); i++ }
                    val hunks = ArrayList<Hunk>()
                    var header = ""
                    var cur = ArrayList<Pair<Char, String>>()
                    var eof = false
                    fun flush() { if (cur.isNotEmpty()) hunks += Hunk(header, cur, eof); cur = ArrayList(); eof = false }
                    while (i < lines.size && !(lines[i].startsWith("*** ") && lines[i].trim() != "*** End of File")) {
                        val l = lines[i]
                        when {
                            l.startsWith("@@") -> { flush(); header = l.removePrefix("@@").trim().removeSuffix("@@").trim() }
                            l.trim() == "*** End of File" -> eof = true
                            l.startsWith("+") -> cur += '+' to l.substring(1)
                            l.startsWith("-") -> cur += '-' to l.substring(1)
                            l.startsWith(" ") -> cur += ' ' to l.substring(1)
                            l.isEmpty() -> cur += ' ' to ""
                            else -> cur += ' ' to l
                        }
                        i++
                    }
                    flush()
                    if (hunks.isEmpty() && moveTo == null) throw PatchException("*** Update File: $path has no hunks")
                    ops += Op.Update(path, moveTo, hunks)
                }
                line.isBlank() -> i++
                else -> throw PatchException("unexpected line ${i + 1}: \"${line.take(80)}\" (each section must start with *** Add File:, *** Update File: or *** Delete File:)")
            }
        }
        return ops
    }

    fun apply(source: String, hunks: List<Hunk>, path: String): String {
        val crlf = source.contains("\r\n")
        val trailingNl = source.endsWith("\n")
        val lines = source.replace("\r\n", "\n").removeSuffix("\n").split("\n").toMutableList()
        if (source.isEmpty()) lines.clear()
        var cursor = 0
        for ((n, h) in hunks.withIndex()) {
            val old = h.lines.filter { it.first != '+' }.map { it.second }
            val new = h.lines.filter { it.first != '-' }.map { it.second }
            var from = cursor
            if (h.header.isNotEmpty()) {
                val at = (cursor until lines.size).firstOrNull { lines[it].contains(h.header) || lines[it].trim() == h.header.trim() }
                if (at != null) from = if (old.isNotEmpty() && old.first().trim() == lines[at].trim()) at else at + 1
            }
            if (old.isEmpty()) {
                // A hunk with no context lines adds after its @@ anchor, or at the end of the file.
                val insertAt = if (h.endOfFile || h.header.isEmpty()) lines.size else from.coerceAtMost(lines.size)
                lines.addAll(insertAt, new); cursor = insertAt + new.size; continue
            }
            val at = seek(lines, old, from, h.endOfFile) ?: seek(lines, old, 0, h.endOfFile)
                ?: throw PatchException("hunk ${n + 1} in $path does not match the file. Expected these lines:\n" +
                    old.take(8).joinToString("\n") { "  $it" } + (TextMatch.missHint(lines.joinToString("\n"), old.joinToString("\n"), new.joinToString("\n"))?.let { "\n$it" } ?: ""))
            repeat(old.size) { lines.removeAt(at) }
            lines.addAll(at, new)
            cursor = at + new.size
        }
        var out = lines.joinToString("\n")
        if (trailingNl || source.isEmpty()) out += "\n"
        return if (crlf) out.replace("\n", "\r\n") else out
    }

    private fun seek(lines: List<String>, pattern: List<String>, start: Int, eof: Boolean): Int? {
        val keys = listOf<(String) -> String>({ it }, { it.trimEnd() }, { TextMatch.normalizeChars(it).trim() })
        for (key in keys) {
            val p = pattern.map(key)
            val range = if (eof) listOf(lines.size - pattern.size).filter { it >= 0 } else (start..lines.size - pattern.size).toList()
            for (i in range) if ((pattern.indices).all { key(lines[i + it]) == p[it] }) return i
        }
        return null
    }
}
