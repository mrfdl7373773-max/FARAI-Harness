package dev.harness.core.commands

import dev.harness.core.commands.builtins.Builtins

class CommandRegistry(commands: List<Command> = Builtins.all()) {

    private val byName = LinkedHashMap<String, Command>()
    private val byAlias = HashMap<String, Command>()

    init { commands.forEach { register(it) } }

    fun register(c: Command) {
        require(c.name.matches(Regex("[a-z][a-z0-9-]*"))) { "Invalid command name: ${c.name}" }
        require(c.name !in byName && c.name !in byAlias) { "Duplicate command: ${c.name}" }
        byName[c.name] = c
        c.aliases.forEach { a ->
            require(a !in byName && a !in byAlias) { "Duplicate alias: $a" }
            byAlias[a] = c
        }
    }

    val all: List<Command> get() = byName.values.toList()

    fun resolve(name: String): Command? {
        val n = name.removePrefix("/").lowercase()
        return byName[n] ?: byAlias[n]
    }

    fun isCommand(input: String): Boolean = input.trimStart().startsWith("/") && !input.trimStart().startsWith("//")

    fun parse(input: String): Invocation? {
        val t = input.trim()
        if (!isCommand(t)) return null
        val body = t.removePrefix("/")
        val name = body.takeWhile { !it.isWhitespace() }.lowercase()
        if (name.isEmpty()) return null
        val raw = body.drop(name.length).trim()
        return Invocation(name, tokenize(raw), raw)
    }

    suspend fun dispatch(input: String, ctx: CommandContext): CommandResult {
        val inv = parse(input) ?: return CommandResult.Error("Not a command: $input")
        val cmd = resolve(inv.name) ?: run {
            val close = suggest(inv.name).take(3).joinToString(", ") { "/${it.name}" }
            return CommandResult.Error("Unknown command /${inv.name}." + if (close.isNotEmpty()) " Did you mean $close?" else " Type /help for the list.")
        }
        return try {
            cmd.execute(inv.copy(name = cmd.name), ctx)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CommandResult.Error("/${cmd.name} failed: ${e.message ?: e::class.java.simpleName}")
        }
    }

    /** Autocomplete for the text currently in the input bar. */
    fun suggestions(input: String): List<Suggestion> {
        if (!input.startsWith("/")) return emptyList()
        val body = input.removePrefix("/")
        val space = body.indexOf(' ')
        if (space < 0) {
            return suggest(body).map { Suggestion("/${it.name}", it.usage, it.description, "/${it.name} ") }
        }
        val cmd = resolve(body.substring(0, space)) ?: return emptyList()
        val after = body.substring(space + 1)
        if (after.contains(' ')) return emptyList()
        return cmd.subcommands.filter { it.startsWith(after, ignoreCase = true) && it != after }
            .map { Suggestion("/${cmd.name} $it", "", cmd.description, "/${cmd.name} $it ") }
    }

    data class Suggestion(val label: String, val usage: String, val description: String, val insert: String)

    private fun suggest(prefix: String): List<Command> {
        val p = prefix.lowercase()
        val starts = all.filter { it.name.startsWith(p) || it.aliases.any { a -> a.startsWith(p) } }
        if (starts.isNotEmpty() || p.isEmpty()) return starts.ifEmpty { all }
        return all.filter { it.name.contains(p) || distance(it.name, p) <= 2 }
    }

    fun grouped(): Map<CommandGroup, List<Command>> =
        all.groupBy { it.group }.toSortedMap(compareBy { it.ordinal }).mapValues { (_, v) -> v.sortedBy { it.name } }

    companion object {
        /** Splits arguments on whitespace, honouring "double" and 'single' quotes. */
        fun tokenize(s: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            var any = false
            for (c in s) {
                when {
                    quote != null && c == quote -> quote = null
                    quote != null -> cur.append(c)
                    c == '"' || c == '\'' -> { quote = c; any = true }
                    c.isWhitespace() -> { if (cur.isNotEmpty() || any) { out += cur.toString(); cur.clear(); any = false } }
                    else -> cur.append(c)
                }
            }
            if (cur.isNotEmpty() || any) out += cur.toString()
            return out
        }

        private fun distance(a: String, b: String): Int {
            val dp = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) dp[i][0] = i
            for (j in 0..b.length) dp[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            return dp[a.length][b.length]
        }
    }
}
