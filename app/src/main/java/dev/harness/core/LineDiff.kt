package dev.harness.core

enum class DiffKind { SAME, ADDED, REMOVED, CHANGED }

/** One row of a side-by-side diff. Left is the default, right is the current file. */
data class DiffRow(
    val kind: DiffKind,
    val leftNo: Int?,
    val left: String?,
    val rightNo: Int?,
    val right: String?,
)

object LineDiff {

    private const val MAX_CELLS = 4_000_000L

    fun sideBySide(leftText: String, rightText: String): List<DiffRow> {
        val a = leftText.lines()
        val b = rightText.lines()
        val ops = ops(a, b)
        // Pair adjacent REMOVED+ADDED runs into CHANGED rows for a readable side-by-side view.
        val rows = mutableListOf<DiffRow>()
        var i = 0
        while (i < ops.size) {
            val op = ops[i]
            if (op.kind == DiffKind.REMOVED) {
                val removed = mutableListOf<Op>()
                while (i < ops.size && ops[i].kind == DiffKind.REMOVED) removed += ops[i++]
                val added = mutableListOf<Op>()
                while (i < ops.size && ops[i].kind == DiffKind.ADDED) added += ops[i++]
                val n = maxOf(removed.size, added.size)
                for (k in 0 until n) {
                    val r = removed.getOrNull(k)
                    val ad = added.getOrNull(k)
                    val kind = when {
                        r != null && ad != null -> DiffKind.CHANGED
                        r != null -> DiffKind.REMOVED
                        else -> DiffKind.ADDED
                    }
                    rows += DiffRow(kind, r?.leftNo, r?.text, ad?.rightNo, ad?.text)
                }
            } else {
                rows += when (op.kind) {
                    DiffKind.SAME -> DiffRow(DiffKind.SAME, op.leftNo, op.text, op.rightNo, op.text)
                    else -> DiffRow(DiffKind.ADDED, null, null, op.rightNo, op.text)
                }
                i++
            }
        }
        return rows
    }

    /** Unified diff text with a few lines of context, for chat output. */
    fun unified(leftName: String, rightName: String, leftText: String, rightText: String, context: Int = 3): String {
        val ops = ops(leftText.lines(), rightText.lines())
        if (ops.all { it.kind == DiffKind.SAME }) return ""
        val sb = StringBuilder()
        sb.appendLine("--- $leftName")
        sb.appendLine("+++ $rightName")
        val changed = ops.indices.filter { ops[it].kind != DiffKind.SAME }
        var idx = 0
        while (idx < changed.size) {
            val start = maxOf(0, changed[idx] - context)
            var end = minOf(ops.size - 1, changed[idx] + context)
            var j = idx + 1
            while (j < changed.size && changed[j] - context <= end + 1) {
                end = minOf(ops.size - 1, changed[j] + context); j++
            }
            val first = ops[start]
            sb.appendLine("@@ -${first.leftNo ?: (first.rightNo ?: 1)} +${first.rightNo ?: (first.leftNo ?: 1)} @@")
            for (k in start..end) {
                val o = ops[k]
                val prefix = when (o.kind) { DiffKind.ADDED -> "+"; DiffKind.REMOVED -> "-"; else -> " " }
                sb.append(prefix).appendLine(o.text)
            }
            idx = j
        }
        return sb.toString().trimEnd()
    }

    private data class Op(val kind: DiffKind, val leftNo: Int?, val rightNo: Int?, val text: String)

    private fun ops(a: List<String>, b: List<String>): List<Op> {
        // Trim common prefix/suffix to keep the LCS table small.
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.subList(pre, a.size - suf)
        val bm = b.subList(pre, b.size - suf)
        val out = mutableListOf<Op>()
        for (k in 0 until pre) out += Op(DiffKind.SAME, k + 1, k + 1, a[k])
        if (am.size.toLong() * bm.size.toLong() > MAX_CELLS) {
            am.forEachIndexed { k, s -> out += Op(DiffKind.REMOVED, pre + k + 1, null, s) }
            bm.forEachIndexed { k, s -> out += Op(DiffKind.ADDED, null, pre + k + 1, s) }
        } else {
            val n = am.size
            val m = bm.size
            val lcs = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                lcs[i][j] = if (am[i] == bm[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    am[i] == bm[j] -> { out += Op(DiffKind.SAME, pre + i + 1, pre + j + 1, am[i]); i++; j++ }
                    lcs[i + 1][j] >= lcs[i][j + 1] -> { out += Op(DiffKind.REMOVED, pre + i + 1, null, am[i]); i++ }
                    else -> { out += Op(DiffKind.ADDED, null, pre + j + 1, bm[j]); j++ }
                }
            }
            while (i < n) { out += Op(DiffKind.REMOVED, pre + i + 1, null, am[i]); i++ }
            while (j < m) { out += Op(DiffKind.ADDED, null, pre + j + 1, bm[j]); j++ }
        }
        for (k in 0 until suf) {
            val ai = a.size - suf + k
            val bi = b.size - suf + k
            out += Op(DiffKind.SAME, ai + 1, bi + 1, a[ai])
        }
        return out
    }
}
