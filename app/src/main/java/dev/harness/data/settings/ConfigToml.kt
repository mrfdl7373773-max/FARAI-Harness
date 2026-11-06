package dev.harness.data.settings

/**
 * Minimal TOML codec for the subset of TOML used by config.toml:
 * [tables], string / number / boolean values and arrays of strings.
 * The API key is never written.
 */
object ConfigToml {

    fun encode(s: AppSettings): String = buildString {
        appendLine("# FARAI Harness configuration. The API key is stored encrypted and is not exported.")
        appendLine()
        appendLine("[provider]")
        kv("base_url", str(s.provider.baseUrl))
        kv("model", str(s.provider.model))
        kv("temperature", s.provider.temperature.toString())
        kv("max_tokens", s.provider.maxTokens.toString())
        kv("max_turns", s.provider.maxTurns.toString())
        kv("timeout", s.provider.timeoutSeconds.toString())
        kv("context_window", s.provider.contextWindow.toString())
        kv("reasoning_effort", str(s.provider.reasoningEffort))
        appendLine()
        appendLine("[agent]")
        kv("repo_map", s.agent.repoMap.toString())
        kv("compaction", s.agent.compaction.toString())
        kv("context_threshold", s.agent.contextThreshold.toString())
        kv("subagents", s.agent.subagents.toString())
        kv("auto_planning", s.agent.autoPlanning.toString())
        kv("bash_timeout", s.agent.bashTimeoutSeconds.toString())
        kv("bash_output_cap", s.agent.bashOutputCap.toString())
        kv("use_linux", s.agent.useLinux.toString())
        kv("parallel_tools", s.agent.parallelTools.toString())
        kv("auto_title", s.agent.autoTitle.toString())
        kv("goal_max_rounds", s.agent.goalMaxRounds.toString())
        kv("approval_mode", str(s.agent.approvalMode))
        kv("memory", s.agent.memory.toString())
        kv("reflect_every", s.agent.reflectEvery.toString())
        kv("reflection_model", str(s.agent.reflectionModel))
        appendLine()
        appendLine("[safety]")
        kv("allowlist", list(s.safety.allowlist))
        kv("blocklist", list(s.safety.blocklist))
        kv("confirm_list", list(s.safety.confirmList))
        appendLine()
        appendLine("[ui]")
        kv("theme", str(s.ui.theme.name.lowercase()))
        kv("font_scale", s.ui.fontScale.toString())
        kv("markdown", s.ui.markdown.toString())
        kv("dynamic_color", s.ui.dynamicColor.toString())
        kv("language", str(s.ui.language))
    }

    /** Parses TOML text and applies recognised keys on top of [base]. Unknown keys are ignored. */
    fun decode(text: String, base: AppSettings): AppSettings {
        val values = parse(text)
        fun s(k: String) = values[k] as? String
        fun b(k: String) = (values[k] as? Boolean)
        fun d(k: String) = (values[k] as? Double)
        fun l(k: String) = (values[k] as? List<*>)?.filterIsInstance<String>()
        val p = base.provider
        val a = base.agent
        val sf = base.safety
        val u = base.ui
        return base.copy(
            provider = p.copy(
                baseUrl = s("provider.base_url") ?: p.baseUrl,
                model = s("provider.model") ?: p.model,
                temperature = d("provider.temperature")?.toFloat() ?: p.temperature,
                maxTokens = d("provider.max_tokens")?.toInt() ?: p.maxTokens,
                maxTurns = d("provider.max_turns")?.toInt() ?: p.maxTurns,
                timeoutSeconds = d("provider.timeout")?.toInt() ?: p.timeoutSeconds,
                contextWindow = d("provider.context_window")?.toInt() ?: p.contextWindow,
                reasoningEffort = s("provider.reasoning_effort")?.let { dev.harness.net.ReasoningEffort.normalize(it) ?: "" } ?: p.reasoningEffort,
            ),
            agent = a.copy(
                repoMap = b("agent.repo_map") ?: a.repoMap,
                compaction = b("agent.compaction") ?: a.compaction,
                contextThreshold = d("agent.context_threshold")?.toFloat() ?: a.contextThreshold,
                subagents = b("agent.subagents") ?: a.subagents,
                autoPlanning = b("agent.auto_planning") ?: a.autoPlanning,
                bashTimeoutSeconds = d("agent.bash_timeout")?.toInt() ?: a.bashTimeoutSeconds,
                bashOutputCap = d("agent.bash_output_cap")?.toInt() ?: a.bashOutputCap,
                useLinux = b("agent.use_linux") ?: a.useLinux,
                parallelTools = b("agent.parallel_tools") ?: a.parallelTools,
                autoTitle = b("agent.auto_title") ?: a.autoTitle,
                goalMaxRounds = d("agent.goal_max_rounds")?.toInt() ?: a.goalMaxRounds,
                memory = b("agent.memory") ?: a.memory,
                reflectEvery = d("agent.reflect_every")?.toInt() ?: a.reflectEvery,
                reflectionModel = s("agent.reflection_model") ?: a.reflectionModel,
                approvalMode = s("agent.approval_mode")?.takeIf { it in setOf("ask", "edits", "always") } ?: a.approvalMode,
            ),
            safety = sf.copy(
                allowlist = l("safety.allowlist") ?: sf.allowlist,
                blocklist = l("safety.blocklist") ?: sf.blocklist,
                confirmList = l("safety.confirm_list") ?: sf.confirmList,
            ),
            ui = u.copy(
                theme = s("ui.theme")?.let { t -> ThemeMode.entries.firstOrNull { it.name.equals(t, true) } } ?: u.theme,
                fontScale = d("ui.font_scale")?.toFloat() ?: u.fontScale,
                markdown = b("ui.markdown") ?: u.markdown,
                dynamicColor = b("ui.dynamic_color") ?: u.dynamicColor,
                language = s("ui.language")?.lowercase()?.takeIf { it == "en" || it == "id" } ?: u.language,
            ),
        )
    }

    internal fun parse(text: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        var table = ""
        for (raw in text.lines()) {
            val line = stripComment(raw).trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                table = line.substring(1, line.length - 1).trim()
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = parseValue(line.substring(eq + 1).trim()) ?: continue
            out[if (table.isEmpty()) key else "$table.$key"] = value
        }
        return out
    }

    private fun parseValue(v: String): Any? = when {
        v == "true" -> true
        v == "false" -> false
        v.startsWith("\"") -> unquote(v)
        v.startsWith("[") -> splitArray(v.removePrefix("[").removeSuffix("]")).mapNotNull { unquote(it.trim()) }
        else -> v.toDoubleOrNull()
    }

    private fun splitArray(body: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var inStr = false
        var esc = false
        for (c in body) {
            when {
                esc -> { cur.append(c); esc = false }
                c == '\\' && inStr -> { cur.append(c); esc = true }
                c == '"' -> { cur.append(c); inStr = !inStr }
                c == ',' && !inStr -> { parts += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) parts += cur.toString()
        return parts.filter { it.isNotBlank() }
    }

    private fun unquote(v: String): String? {
        if (v.length < 2 || !v.startsWith("\"") || !v.endsWith("\"")) return null
        val body = v.substring(1, v.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (val n = body[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> { sb.append('\\'); sb.append(n) }
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun stripComment(line: String): String {
        var inStr = false
        var esc = false
        for ((i, c) in line.withIndex()) {
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                c == '#' && !inStr -> return line.substring(0, i)
            }
        }
        return line
    }

    private fun StringBuilder.kv(k: String, v: String) { append(k).append(" = ").appendLine(v) }
    private fun str(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    private fun list(v: List<String>) = v.joinToString(prefix = "[", postfix = "]", separator = ", ") { str(it) }
}
