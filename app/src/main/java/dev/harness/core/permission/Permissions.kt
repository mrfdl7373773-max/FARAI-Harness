package dev.harness.core.permission

/** What a permission rule does when it matches. */
enum class PermEffect { ALLOW, ASK, DENY;
    companion object {
        fun parse(s: String): PermEffect? = when (s.trim().lowercase()) {
            "allow", "yes", "on" -> ALLOW
            "ask", "confirm" -> ASK
            "deny", "no", "block", "off" -> DENY
            else -> null
        }
    }
}

/**
 * `<action> <pattern> <effect>`. Actions are tool-level names (`bash`, `edit`, `read`, `web_fetch`,
 * `task`, `doom_loop`, `external_directory`, ...); `edit` covers write, edit, multiedit and patch.
 * Patterns are wildcards: `*` matches anything (including `/`), `?` one character.
 */
data class PermRule(val action: String, val pattern: String, val effect: PermEffect, val source: String = "") {
    fun matches(action: String, resource: String): Boolean =
        Wildcard.match(action, this.action) && Wildcard.match(resource, pattern)

    override fun toString(): String = "$action $pattern ${effect.name.lowercase()}"
}

object Wildcard {
    fun match(value: String, pattern: String): Boolean {
        if (pattern == "*") return true
        return toRegex(pattern).matches(value)
    }

    private val cache = HashMap<String, Regex>()

    @Synchronized
    fun toRegex(pattern: String): Regex = cache.getOrPut(pattern) {
        val sb = StringBuilder()
        for (ch in pattern) when (ch) {
            '*' -> sb.append("[\\s\\S]*")
            '?' -> sb.append("[\\s\\S]")
            else -> sb.append(Regex.escape(ch.toString()))
        }
        Regex(sb.toString())
    }
}

object PermissionRules {
    /**
     * Parses rule lines. Blank lines and `#` comments are ignored, and so is anything inside ```
     * fences so the file can be documented in Markdown. A pattern may contain spaces
     * (`bash git push* ask`): the first word is the action, the last the effect, the rest the pattern.
     */
    fun parse(text: String, source: String): List<PermRule> {
        val out = ArrayList<PermRule>()
        // In a Markdown file only the fenced blocks hold rules, so prose around them is safe.
        val fenced = text.lines().any { it.trim().startsWith("```") }
        var inFence = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("```")) { inFence = !inFence; continue }
            if (fenced && !inFence) continue
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(">") || line.startsWith("-") && !line.contains(' ')) continue
            val cleaned = line.removePrefix("- ").removePrefix("* ").substringBefore(" #").trim().trim('`')
            val parts = cleaned.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val effect = PermEffect.parse(parts.last()) ?: continue
            val action = parts.first()
            if (!Regex("[A-Za-z0-9_*?.-]+").matches(action)) continue
            val pattern = if (parts.size == 2) "*" else parts.subList(1, parts.size - 1).joinToString(" ")
            out += PermRule(action, pattern, effect, source)
        }
        return out
    }
}

/** The user's answer to a permission question. */
sealed interface Approval {
    data object Once : Approval
    /** Allow, and stop asking for these patterns for the rest of this session. */
    data object Always : Approval
    /** Refuse. With [feedback], the model is told what to do instead and keeps going. */
    data class Deny(val feedback: String? = null) : Approval
}

data class ApprovalRequest(
    val title: String,
    val detail: String,
    val action: String = "",
    /** Patterns an "Always" answer remembers, e.g. `git push*` or a folder pattern. Empty = cannot remember. */
    val always: List<String> = emptyList(),
)

data class PermissionCheck(val effect: PermEffect, val rule: PermRule?, val resource: String)

/**
 * Evaluates rules (last match wins) and remembers "Always" answers per session.
 * Rules come from, in order: prompts/permissions.md, then the active agent's `permission:` block.
 * With no matching rule an action is allowed.
 */
class PermissionEngine(private val ruleSources: () -> List<PermRule>) {

    fun evaluate(action: String, resource: String, agentRules: List<PermRule>, remembered: Set<String>): PermissionCheck {
        val rules = ruleSources() + agentRules
        val hit = rules.lastOrNull { it.matches(action, resource) }
        val effect = hit?.effect ?: PermEffect.ALLOW
        if (effect == PermEffect.ASK && remembered.any { key -> rememberedMatches(key, action, resource) }) {
            return PermissionCheck(PermEffect.ALLOW, hit, resource)
        }
        return PermissionCheck(effect, hit, resource)
    }

    /** Most severe outcome over several resources (deny > ask > allow). */
    fun evaluateAll(action: String, resources: List<String>, agentRules: List<PermRule>, remembered: Set<String>): List<PermissionCheck> =
        resources.ifEmpty { listOf("*") }.map { evaluate(action, it, agentRules, remembered) }

    companion object {
        fun rememberKey(action: String, pattern: String) = "$action\u0000$pattern"

        private fun rememberedMatches(key: String, action: String, resource: String): Boolean {
            val a = key.substringBefore('\u0000')
            val p = key.substringAfter('\u0000')
            return Wildcard.match(action, a) && Wildcard.match(resource, p)
        }

        /** What "Always" should remember for a shell command: its program (and sub-command) plus `*`. */
        fun commandPattern(command: String): String {
            val words = command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return "*"
            val multi = setOf("git", "npm", "pnpm", "yarn", "pip", "pip3", "apk", "cargo", "go", "docker", "gradle", "./gradlew", "python", "python3", "node", "npx", "kubectl", "uv")
            val head = if (words.first() in multi && words.size > 1 && !words[1].startsWith("-")) "${words[0]} ${words[1]}" else words[0]
            return "$head*"
        }

        /** What "Always" should remember for a path: everything in its folder. */
        fun pathPattern(path: String): String {
            val dir = path.substringBeforeLast('/', "")
            return if (dir.isEmpty()) "*" else "$dir/*"
        }
    }
}
