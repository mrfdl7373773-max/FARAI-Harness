# FARAI Harness

A native Android coding agent that lives on your phone, with one defining property:
**every instruction the model receives is a file you can read, edit, diff, roll back and reset from Settings.**
No hardcoded prompts, no hidden system text, no protected files.

Kotlin · Jetpack Compose · iOS-style design (large titles, inset grouped lists, bubbles, frosted bars, spring animations, haptics) · Alpine Linux via PRoot · minSdk 26 · targetSdk 34 · JDK 17

---

## Build

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew connectedDebugAndroidTest   # instrumented test (device/emulator)
```

Open in Android Studio (Koala or newer) or build from the command line with JDK 17 and the Android 34 SDK.

## First run

1. On first launch the app copies `assets/defaults/prompts/` → `filesDir/prompts/`.
2. **All files access** is requested first: the app opens Android's *Allow access to manage all files* switch for
   you (once). Granted, the file tools and the Linux shell can reach shared storage (`/sdcard`).
3. The onboarding screen renders **`prompts/onboarding.md`** (edit it and the welcome text changes).
4. **Linux setup**: downloads the Alpine minirootfs (~4 MB) and, by default, installs
   `bash python3 py3-pip git curl nano` with apk (~120 MB). Can run in the background or be skipped.
5. **Configure Provider**: base URL, API key, model → *Test Connection* calls `GET /models`.
6. *Done* lands you on **Sessions** (empty state). Tap **+** to start.

Works with any OpenAI-compatible endpoint: OpenAI, OpenRouter (`https://openrouter.ai/api/v1`),
Groq, Together, DeepSeek, a local llama.cpp / LM Studio / Ollama (`http://<lan-ip>:11434/v1`) server, etc.

---

## The editable layer

| File | Controls |
|---|---|
| `identity.md` | Who the agent is: name, voice, values |
| `system.md` | Priorities, working style, answer format |
| `tools/*.md` | The description of each tool exactly as the model sees it (14 files) |
| `compaction.md` | How older turns are summarised mid-session |
| `planning.md` | Planning template for complex tasks (Planning mode, or auto for long requests) |
| `verify.md` | Self-check footer; in Verify mode it is also sent as a follow-up check |
| `refusal.md` | How the agent declines |
| `error_recovery.md` | What to do after a tool fails |
| `onboarding.md` | The first-run welcome screen |
| `templates/*.md` | Named task templates for `/template` |

**System message assembly** (rebuilt from disk on *every* request — there is no cache):

```
identity.md
system.md
refusal.md
error_recovery.md
<environment>  Android version, API, ABI, device, workspace, model, mode, date, tools  (programmatic)
<todo>         current todo list                                                     (programmatic)
<repo_map>     workspace index, when enabled                                         (programmatic)
planning.md    only in Planning mode or for complex requests
verify.md      footer
```

Tool schemas sent to the model: `description` = contents of `prompts/tools/<tool>.md`; `parameters` = JSON schema in code.
Use `/context` to see the exact system message and `/payload` to see the full last request.

### Example: make it a pirate

1. **Settings → Prompts → Identity → identity.md**
2. Replace the first line with `You are a pirate coding agent. Speak like a pirate.`
3. Tap ✓ (save). The title's `●` dirty dot disappears and the save is stored in `.history/`.
4. Go back to a chat and send anything — the very next reply is in pirate voice.
5. Tap ⇄ for a side-by-side diff against the shipped default, ⟲ to reset, or 🕘 to roll back to any of the last 50 saves.

From chat, the same repository is behind:
```
/prompt list
/prompt show identity
/prompt edit identity
/prompt diff identity
/prompt reset identity
```

Prompts screen extras: import a `.md` into any file (SAF `ACTION_OPEN_DOCUMENT`), load a whole prompt set from a `.zip`,
export every prompt to a `.zip` (SAF `CREATE_DOCUMENT`, e.g. into Downloads), *Reset all* (type RESET to confirm),
and create new files (e.g. `templates/deploy.md`, or `tools/<name>.md` for a custom tool).

If a prompt file is missing, requests fail with a clear error and the editor shows **Prompt file missing → Restore from defaults**.

### Switching models

- **Settings → Provider → Choose from provider…** lists `GET /models` with a filter field, or
- in chat: `/model list`, then `/model anthropic/claude-sonnet-4` (takes effect on the next message).

---

## Linux (Alpine via PRoot)

Android's own shell is toybox: no python, pip, git or package manager. FARAI Harness runs **Alpine Linux** inside the
app with [PRoot](https://github.com/green-green-avk/proot) (ptrace-based, no root):

- PRoot ships in the APK as `jniLibs/<abi>/libproot.so` + `libproot-loader*.so` (arm64-v8a, armeabi-v7a, x86_64),
  packaged with `useLegacyPackaging` so Android extracts them to `nativeLibraryDir`, the only place an app may
  execute files from on Android 10+.
- The Alpine minirootfs is downloaded from `dl-cdn.alpinelinux.org` (3.24 branch, sha256-verified; apk-tools 3 cannot write under PRoot because it links O_TMPFILE files through /proc/self/fd, so FARAI installs the static apk-tools 2.14 from the 3.21 branch as /usr/local/sbin/apk). Ubuntu offers a choice in Linux setup: 24.04 LTS (default, lighter, newest point release such as 24.04.5) or 26.04.1 LTS; switching replaces the install after a confirm. 26.04's default uutils coreutils break under PRoot, so FARAI switches to GNU coreutils (coreutils-from-gnu) and unpacked
  with a small built-in tar extractor into `filesDir/linux/alpine`.
- Inside the guest: fake root, cwd `/workspace` (bound to the app workspace), `/sdcard` (shared storage, when
  granted), `/dev /proc /sys` bound, DNS `8.8.8.8`, `PIP_BREAK_SYSTEM_PACKAGES=1` so `pip install x` just works.
- The `bash` tool runs `/bin/sh -c` in Alpine when Linux is ready and *Settings → Linux → Run bash in Linux* is on;
  otherwise it falls back to `/system/bin/sh`. The environment block tells the model which shell it has.
- `/linux` and `/pip` let you drive it without the model:

```
/linux install                 # or Settings → Linux
/linux add nodejs npm gcc musl-dev
/linux run python3 -c "print(2**100)"
/linux python3 --version       # anything after /linux runs as a command
/pip install requests rich
/linux off | on | status | remove
```

Limits: no systemd, no real root, no kernel modules, raw sockets (ping) may fail, and processes stop when a command
times out. glibc-only binaries need `apk add gcompat`.

## Skills (SKILL.md)

FARAI Harness reads Agent Skills in the common SKILL.md format: a folder with a `SKILL.md`
(YAML front matter with `name` and `description`, then instructions) plus any helper files.

- Skills live in the workspace at `.farai/skills/<name>/`; `.claude/skills/<name>/` is read too,
  for compatibility.
- Only each skill's name and description go into the system prompt (`prompts/skills.md` is the
  editable intro). When a task matches, the model loads the full instructions with the `skill`
  tool, and helper files with `skill(name, file)` or by path.
- A starter `skill-creator` skill is installed on first launch, so you can just ask the agent to
  "make a skill for …".
- `/skills` manages them without the model:

```
/skills                         list (● on, ○ off)
/skills new pdf-report Makes PDF reports from CSV. Use when asked for a report.
/skills import /sdcard/Download/my-skill.zip     folder, .md or .zip (--force to replace)
/skills show pdf-report
/skills use pdf-report data.csv  (or /skill pdf-report data.csv)
/skills off pdf-report | on | remove
```

## Tools

File tools are confined to `filesDir/workspace/` and, with All files access, shared storage. Paths can be written the
way the Linux guest sees them: `/workspace/...` and `/sdcard/...`. Anything else (and path traversal) is rejected.

| Tool | What it does |
|---|---|
| `bash` | Alpine Linux (PRoot) or `/system/bin/sh`; `workdir`, `timeout_seconds` (≤1800), `description` for the card, `background: true` for servers/long builds. Big output → head + tail, full log saved under `.farai/tool-output/` |
| `job` | `list` / `output` / `wait` / `kill` background jobs; finished jobs are announced to the model automatically |
| `read` | `N: line` numbering, up to 2000 lines, directory listing, "did you mean" on typos, attaches nested `AGENTS.md`/`CLAUDE.md` |
| `write` | Create/overwrite (`append: true` for long files); refuses to replace a file the model never read |
| `edit` | Tiered match: exact → typographic quotes/dashes → trailing whitespace → indentation (re-indents) → whitespace runs; CRLF kept; misses return the closest numbered lines or "already applied"; returns a diff |
| `multiedit` | Several edits to one file, atomic |
| `patch` | `*** Begin Patch` multi-file Add/Update/Move/Delete, all-or-nothing |
| `glob` / `grep` | Paths newest first; regex or `literal` search |
| `web_fetch` | `markdown` / `text` / `html`; host-level permission |
| `todo` | `pending`/`in_progress`/`done`/`cancelled`, shown as a chip |
| `task` | Subagent by `agent` (explore, general, reviewer, your own), `task_id` to continue one; several in one reply run in parallel |
| `question` | Multiple-choice questions to you (sheet with options + free text) |
| `skill` | Loads an Agent Skill |
| `clipboard_read`, `notification_post`, `intent_open`, `device_info` | Android integrations |
| `finish` | Ends the loop with a summary |

Models trained on other harnesses are forgiven: `shell`, `str_replace`, `apply_patch`, `TodoWrite`, `filePath`,
`"limit": "20"`, double-encoded JSON, trailing commas… are repaired and the model is told the right name.

### Adding a custom tool (extension point)

1. Create `app/src/main/java/dev/harness/tools/MyTool.kt` implementing `Tool` (`name`, `parameters` schema, `execute`).
2. Register it in `ToolRegistry.core()` (or `android()` if it needs a `Context`).
3. Create `prompts/tools/<name>.md` — in the app (Prompts → +) or under `assets/defaults/prompts/tools/` to ship it.

### Safety and permissions

Two layers, both yours:

1. **Settings → Safety** — allowlist / blocklist / confirm list for shell commands (`tool:<name>` blocks or confirms a tool).
2. **`prompts/permissions.md`** — rules `<action> <pattern> <allow|ask|deny>`, last match wins, e.g. `bash git push* ask`,
   `read *.env ask`, `edit * ask`, `web_fetch * deny`. Agent files add their own rules. An *ask* shows a card with
   **Deny / Always / Allow** and an optional note — "Deny + note" tells the agent what to do instead and it carries on.
   *Always* lasts for the session (`/permissions forget`). `doom_loop` asks when the agent repeats the same call 3×.

---

## MCP servers

FARAI is an MCP client (Model Context Protocol, revision 2025-06-18).

- **Config:** `prompts/mcp.md` (the ```json block), plus the workspace's `.mcp.json`, `farai.json` and `.farai/mcp.json`; a later file wins per server name. Both FARAI's (`"mcp": {name: {type: local|remote, command: [...], environment, url, headers, enabled, timeout}}`) and the common (`"mcpServers": {name: {command, args, env, url, type: http|sse}}`) formats are read, jsonc comments allowed.
- **Transports:** stdio (newline-delimited JSON-RPC; the process runs inside Alpine Linux when installed, so `npx`/`uvx`/`python` servers work), Streamable HTTP (POST, JSON or SSE answers, `Mcp-Session-Id`), and legacy HTTP+SSE.
- **Lifecycle:** servers connect in parallel right before an agent run, reconnect when their config changes or the connection drops, and a failed server is not retried until its config changes or `/mcp connect`. `tools/list_changed` refreshes the catalog.
- **Tools:** each server tool becomes `<server>_<tool>` (sanitized, ≤64 chars) with the server's input schema. `readOnlyHint` tools may run in parallel. Permission rules apply by tool name (`github_* ask`). The description can be overridden in `prompts/tools/mcp/<tool>.md`. Results: text inline, images/binaries saved to `.farai/tool-output/`, long output capped and spilled to a file, `isError` reported as a tool error.
- **Instructions** a server sends at initialize are added to the system prompt (`<mcp_instructions>`).
- **Resources:** `mcp_resource` tool (list/read) appears when a server offers resources. **Prompts:** `/mcp prompt <server> <name> key=value…` sends a server prompt to the agent.
- **OAuth** (`core/mcp/McpAuth.kt`): a 401 puts the server in "needs sign-in"; `/mcp auth <name>` runs the MCP authorization flow: protected-resource metadata (RFC 9728, from `WWW-Authenticate` or well-known), authorization-server metadata (RFC 8414/OpenID, legacy default endpoints as fallback), dynamic client registration (RFC 7591) or a configured `oauth.clientId`, authorization code + PKCE S256 + `resource` (RFC 8707), browser return on a loopback listener (`127.0.0.1:19876`, RFC 8252). Tokens live in app-private `filesDir/mcp-auth/`, refresh automatically (once on 401, or before expiry), and `/mcp logout` deletes them. `"oauth": false` turns it off.
- **Elicitation:** form mode (string/number/integer/boolean/enum/multi-select, with required, ranges, lengths and formats validated) and URL mode, shown as a sheet/dialog on any screen; Decline and Cancel are reported as such.
- **Sampling:** `sampling/createMessage` runs on the model from Settings → Provider after the user approves (Allow / Always for the server / Deny); `"sampling": true|false` in the config skips the question. Images in sampling messages are replaced by a note (the chat client is text-only).

## Long-term memory

FARAI remembers across sessions.

- **Core memory**: Markdown files at the root of `filesDir/memory` (`persona.md`, `human.md`, …) plus the `MEMORY.md` index. Compiled into the system prompt once per user message, each with `chars="used/limit"` so the model sees memory pressure. Limits: 6,000 chars per core file, 24,000 total, 20,000 per folder file, two folder levels.
- **Deferred memory**: files in folders (`projects/…`, `people/…`). Only folder summaries are in the prompt; the model opens files with the `memory` tool. New files are linked from their folder's `MEMORY.md` automatically.
- **Project memory**: `.farai/memory` in the workspace, addressed as `project/…`.
- **Recall**: the `recall` tool searches every past session, including messages compaction removed (archived to `memory/.recall/`), with date filters and "expand around a hit".
- **Reflection**: a background model call with only `memory` + `recall` reviews the conversation every N user messages (default 6) and before compaction, and updates memory. Its report shows as a 🧠 note in the chat.
- **Nudges**: messages that sound like a preference, correction, fact or "forget this" get a short reminder to update memory.
- **Safety and history**: secrets are refused; every change is logged with before/after, reason and source; any change can be reverted (`/memory history`, `/memory revert <id>`, Settings → Memory).
- Prompts: `memory.md`, `tools/memory.md`, `tools/recall.md`, `memory/reflection.md`, `templates/memory-init.md`. Commands: `/memory`, `/remember`, `/recall`. Settings: `agent.memory`, `agent.reflect_every`, `agent.reflection_model`.

## Agents

Agents are Markdown files with front matter (`name`, `description`, `mode: primary|subagent|all`, `tools`,
`permission`, `max_steps`, `temperature`, `model`, `verify`, `reminder`). Shipped in `prompts/agents/`:

| Agent | Kind | |
|---|---|---|
| **build** | primary | Default, full tools |
| **plan** | primary | Read-only (edits denied except `.farai/plans/`), plans with you |
| **verify** | primary | Build + verify.md self-check before answering |
| **explore** | subagent | Fast read-only code search |
| **general** | subagent | Multi-step work incl. file changes, runs in parallel |
| **reviewer** | subagent | Read-only bug-focused code review |

Add your own with `/agent new <name> [subagent]`, or drop files in the workspace's `.farai/agents/` (also read:
`.claude/agents/`). Primary agents appear in the chat's segmented control; switching sends the model
a `<system-reminder>`.

`prompts/models/*.md` (`match: gpt-*, o3*`) add model-family tuning; the root `AGENTS.md`/`CLAUDE.md` of the workspace is
put in the system prompt (`/init` writes one for you).

---

## Agent loop

`Engine.kt`: system message (identity, system, refusal, error_recovery, environment, project AGENTS.md, skills, active
agent, model overlays, planning, goal, verify) → stream the reply → run tool calls (read-only ones in parallel) → feed
results back → repeat until a text reply, `finish`, or the step limit (agent `max_steps` or *max turns*, default 50).
At the limit one last tool-less call (`max_steps.md`) summarises what is done and what is left.

- **Retries**: 408/409/425/429/5xx and network errors back off 2 s → 10 s (jittered, `Retry-After` honoured), up to 7
  attempts, shown in the chat.
- **Context**: over the threshold, old tool outputs are cleared first (no model call); if still too big, `compaction.md`
  writes a structured checkpoint (Objective / Requirements / Decisions / Work State / Next Move / Relevant Files) that is
  updated, not rewritten, next time. A provider "context too long" error triggers this and a retry.
- **Goals**: `/goal <objective>` keeps the agent going round after round; after each, a separate reviewer call
  (`goal/judge.md`) checks the evidence and answers done / continue / blocked. Max rounds in Settings; stalls pause it.
- **Undo**: write/edit/multiedit/patch snapshot files first; `/undo` restores them along with the chat.
- **Titles**: new sessions get a short model-written title (`title.md`).

Stop cancels the stream or the running tool and repairs dangling tool calls. Sessions persist to Room.

---

## Slash commands

Type `/` in the input bar for autocomplete; `/help` lists everything grouped; `//text` sends a literal message starting with `/`.

| Command | Arguments | Description |
|---|---|---|
| `/help` (`/?`, `/commands`) | `[command]` | Show all commands grouped, or details for one |
| `/config` (`/provider`) | | Open Settings → Provider |
| `/quit` (`/exit`, `/q`) | | Save and close the session, return to Sessions |
| `/prompt` (`/prompts`, `/p`) | `list` · `show` · `edit` · `diff` · `reset` · `history` · `rollback` · `export` · `path` `[name] [version]` | Manage the editable prompt files |
| `/model` (`/models`) | `[list \| <model id>]` | Show the current model, list provider models, or switch |
| `/temp` (`/temperature`) | `[0–2]` | Show or set sampling temperature |
| `/turns` (`/max-turns`) | `[1–200]` | Show or set max agent steps per message |
| `/reasoning` (`/effort`, `/think`) | `[none·minimal·low·medium·high·xhigh·max·default]` | Show or set reasoning effort. OpenRouter gets `reasoning.effort`; other providers get `reasoning_effort`, clamped to known limits (Gemini ≤ high, Claude ≥ low) and retried at the nearest accepted level if the model rejects it |
| `/todo` (`/todos`) | `[clear]` | Show the todo list, or clear it |
| `/clear` | | Clear the conversation context (prompts and todo stay) |
| `/save` | | Force-save the session |
| `/export` (`/share`) | | Share the transcript as text |
| `/agent` (`/mode`, `/agents`) | `[name \| list \| edit <name> \| new <name> [subagent]]` | Switch the agent or manage agent files |
| `/goal` | `<objective> \| status \| pause \| resume \| clear` | Work until a reviewer confirms the objective is met |
| `/init` | `[focus]` | Study the project and write `AGENTS.md` |
| `/review` | `[commit \| branch \| path]` | Review changes for real bugs |
| `/permissions` (`/perms`) | `[edit \| forget]` | Show rules in effect, edit them, forget "always" answers |
| `/jobs` | `[kill <id\|all>]` | Background shell jobs |
| `/mcp` | `[list\|connect\|disconnect\|auth\|logout\|tools\|add\|remove\|edit\|prompts\|prompt\|resources]` | MCP servers, OAuth sign-in |
| `/reset-all` | | Wipe messages, todos, mode, tool toggles; keep provider config |
| `/rename` (`/title`) | `<title>` | Rename the session |
| `/fork` (`/clone`) | `[message count]` | Copy the session (optionally the first N messages) and open the copy |
| `/sessions` (`/find`) | `[text] \| open <id>` | Search past sessions by title/tag/content, or open one |
| `/tag` (`/tags`) | `[add\|remove\|clear] [tags…]` | Show or change session tags |
| `/tools` | `[name]` | List tools with the descriptions the model sees |
| `/tool` | `enable\|disable\|status [name]` | Toggle a tool for this session only |
| `/run` | `<tool> [json]` | Run a tool yourself, outside the model loop |
| `/last` | | Show the last tool result again, in full |
| `/tokens` (`/usage`) | | Context estimate vs window and compaction threshold |
| `/compact` | | Compact now with `compaction.md` |
| `/context` (`/system`) | | Show the exact next system message |
| `/payload` (`/request`) | `[share]` | Dump the raw JSON of the last request |
| `/headers` | | Provider response headers from the last turn |
| `/health` (`/ping`) | | Health-check the provider (latency, model listed?) |
| `/template` (`/tpl`) | `list \| run <name> [details] \| edit <name>` | Start a task from `prompts/templates/` (tags the session `#tpl-<name>`) |
| `/history` | `[tag]` | Prior sessions sharing this session's tags (e.g. earlier runs of a template) |
| `/retry` (`/regen`) | | Discard the last reply and ask again |
| `/undo` | | Remove your last message and everything after it, and restore files the agent changed since |
| `/linux` (`/alpine`, `/sh`) | `status \| install \| add <pkg…> \| del <pkg…> \| run <cmd> \| on \| off \| remove \| settings` | Manage Alpine Linux and run commands in it directly |
| `/pip` | `<pip arguments>` | Run pip inside Alpine, e.g. `/pip install requests` |
| `/skills` | `[list\|show\|use\|new\|import\|on\|off\|remove\|path]` | Agent Skills (SKILL.md): list, create, import, use, turn on or off |

---

## Configuration

Non-secret settings: DataStore. API key: EncryptedSharedPreferences. Everything (minus the key) is mirrored to
`filesDir/config.toml`; Settings → Data can export/import it.

```toml
[provider]
base_url = ""   # your provider, e.g. any OpenAI-compatible /v1 URL
model = ""
temperature = 0.2
max_tokens = 4096
max_turns = 30
timeout = 120
context_window = 128000

[agent]
repo_map = false
compaction = true
context_threshold = 0.8
subagents = true

[safety]
allowlist = []
blocklist = ["rm -rf /", "reboot"]
confirm_list = ["rm ", "mv "]

[ui]
theme = "system"
font_scale = 1.0
markdown = true
```

## Project layout

```
app/src/main/assets/defaults/prompts/   shipped prompt files (the only place defaults live)
app/src/main/java/dev/harness/
  core/linux/  LinuxRuntime (PRoot + Alpine install/exec), TarExtractor, StorageAccess
  core/        Engine, PersonaLoader, PromptRepository, SessionManager, ContextCompactor, RepoMapper, Safety, LineDiff
  core/commands/  Command, CommandRegistry, builtins/
  tools/       Tool, ToolRegistry, 14 tools
  data/        settings (DataStore + encrypted key + TOML), session (Room), prompt (history)
  net/         OpenAiClient (SSE, retry, cancel), SseParser, StreamAssembler, ChatModels
  ui/          theme, nav, chat, sessions, settings, onboarding, setup (storage + Linux), components
app/src/main/jniLibs/   PRoot binaries per ABI (GPL-2.0, see THIRD_PARTY.md)
app/src/test/  PromptRepositoryTest, PromptUpgradeTest, EngineTest, SafetyTest, CompactorTest, CommandRegistryTest,
              SseParserTest, TarExtractorTest, WorkspaceTest
```
