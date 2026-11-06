package dev.harness.core.commands.builtins

import dev.harness.core.commands.Command
import dev.harness.core.commands.CommandContext
import dev.harness.core.commands.CommandGroup
import dev.harness.core.commands.CommandResult
import dev.harness.core.commands.Invocation
import dev.harness.core.commands.NavTarget

/** /linux: manage Linux (Ubuntu or Alpine under PRoot) and run commands in it directly, without the model. */
class LinuxCommand : Command {
    override val name = "linux"
    override val aliases = listOf("alpine", "ubuntu", "sh")
    override val group = CommandGroup.TOOLS
    override val usage = "[status|install [ubuntu|alpine]|add <pkg…>|del <pkg…>|run <cmd>|on|off|remove|settings]"
    override val description = "Linux shell (Ubuntu or Alpine): install, add packages, run commands"
    override val subcommands = listOf("status", "install", "add", "del", "run", "on", "off", "remove", "settings")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val linux = ctx.linux ?: return CommandResult.Error("Linux is not available in this build.")
        val sub = inv.arg(0)?.lowercase() ?: "status"
        val s = ctx.settings()
        fun needReady(): CommandResult? =
            if (!linux.isReady()) CommandResult.Error("Linux is not installed yet. Run /linux install (or open Settings → Linux).") else null
        return when (sub) {
            "status" -> CommandResult.Output(
                linux.status() + "\n\nbash tool: " + (if (s.agent.useLinux) "runs in Linux when ready" else "uses Android sh (/linux on to switch)"),
            )
            "install", "repair" -> {
                val distro = inv.arg(1)?.lowercase()?.takeIf { it == "ubuntu" || it == "alpine" }
                val pkgs = inv.args.drop(if (distro != null) 2 else 1).ifEmpty { null }
                linux.startInstall(pkgs, distro)
                CommandResult.Navigate(NavTarget.Linux, "Installing ${distro?.replaceFirstChar { it.uppercase() } ?: linux.distroName()}…")
            }
            "add", "install-pkg" -> {
                needReady()?.let { return it }
                val pkgs = inv.args.drop(1)
                if (pkgs.isEmpty()) return CommandResult.Error("Usage: /linux add <package…>  e.g. /linux add nodejs npm")
                CommandResult.Output(fence(linux.run(installCmd(linux, pkgs), 1800, 20_000)))
            }
            "del", "rm-pkg" -> {
                needReady()?.let { return it }
                val pkgs = inv.args.drop(1)
                if (pkgs.isEmpty()) return CommandResult.Error("Usage: /linux del <package…>")
                CommandResult.Output(fence(linux.run(if (linux.packageManager() == "apt") "DEBIAN_FRONTEND=noninteractive apt-get remove -y ${pkgs.joinToString(" ")}" else "apk del ${pkgs.joinToString(" ")}", 300, 20_000)))
            }
            "run", "exec" -> {
                needReady()?.let { return it }
                val cmd = inv.rest(1).ifBlank { return CommandResult.Error("Usage: /linux run <command>") }
                CommandResult.Output(fence(linux.run(cmd, s.agent.bashTimeoutSeconds.toLong(), s.agent.bashOutputCap), "console"))
            }
            "on", "off" -> {
                val on = sub == "on"
                ctx.updateSettings { it.copy(agent = it.agent.copy(useLinux = on)) }
                CommandResult.Output(if (on) "The bash tool now runs inside ${linux.distroName()}." else "The bash tool now uses Android's /system/bin/sh.")
            }
            "remove", "uninstall" -> { linux.remove(); CommandResult.Output("Linux removed. Workspace files were not touched.") }
            "settings" -> CommandResult.Navigate(NavTarget.Linux)
            else -> {
                // "/linux python3 --version" runs the whole line.
                needReady()?.let { return it }
                CommandResult.Output(fence(linux.run(inv.rawArgs.trim(), s.agent.bashTimeoutSeconds.toLong(), s.agent.bashOutputCap), "console"))
            }
        }
    }

    companion object {
        val DEFAULT_PACKAGES = listOf("bash", "python3", "py3-pip", "git", "curl", "nano")

        fun installCmd(linux: dev.harness.core.commands.LinuxControl, pkgs: List<String>): String =
            if (linux.packageManager() == "apt") "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${pkgs.joinToString(" ")} || (apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${pkgs.joinToString(" ")})"
            else "apk add --no-cache ${pkgs.joinToString(" ")}"
    }
}

/** /pip: shortcut for pip inside Linux (installs pip first if needed). */
class PipCommand : Command {
    override val name = "pip"
    override val group = CommandGroup.TOOLS
    override val usage = "<pip arguments>"
    override val description = "Run pip inside Linux, e.g. /pip install requests"
    override val subcommands = listOf("install", "list", "show", "uninstall", "freeze")

    override suspend fun execute(inv: Invocation, ctx: CommandContext): CommandResult {
        val linux = ctx.linux ?: return CommandResult.Error("Linux is not available in this build.")
        if (!linux.isReady()) return CommandResult.Error("Linux is not installed yet. Run /linux install first.")
        val args = inv.rawArgs.trim().ifEmpty { "list" }
        val ensure = if (linux.packageManager() == "apt") "DEBIAN_FRONTEND=noninteractive apt-get install -y python3-pip >/dev/null" else "apk add --no-cache python3 py3-pip >/dev/null"
        val cmd = "command -v pip >/dev/null 2>&1 || $ensure; PIP_BREAK_SYSTEM_PACKAGES=1 pip $args"
        return CommandResult.Output(fence(linux.run(cmd, 900, 20_000), "console"))
    }
}
