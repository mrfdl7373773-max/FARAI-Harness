# Permissions

These rules decide which tool calls run on their own, which wait for your
approval, and which are refused. They apply to every agent; an agent file can
add its own rules on top (its `permission:` block), and those win.

How a rule reads: `<action> <pattern> <allow|ask|deny>`

- **action**: the tool-level action. `bash`, `read`, `edit` (covers write,
  edit, multiedit and patch), `glob`, `grep`, `web_fetch`, `web_search`, `task`, `skill`,
  `job`, `todo`, `question`, `external_directory` (anything outside the
  workspace, like /sdcard) and `doom_loop` (the same call three times in a
  row). `*` means every action.
- **pattern**: what the call touches. For `bash` it is each command in the
  line (`git push origin main`), for file tools the workspace-relative path,
  for `web_fetch` the host, for `web_search` the query, for `task` the agent name. `*` matches anything,
  `?` one character.
- **effect**: `allow` runs it, `ask` shows you an approval card (Allow /
  Always / Deny, with an optional note for the agent), `deny` refuses it.

The **last matching rule wins**, and a call no rule matches is allowed.
"Always" remembers the answer for the rest of the session (`/permissions
forget` clears it). Only the lines inside the code block below are read.
The Safety lists in Settings (blocklist, allowlist, confirm list) still apply
on top of these rules.

```
# action             pattern               effect
*                    *                     allow

# Secrets: ask before reading env files, except examples.
read                 *.env                 ask
read                 *.env.*               ask
read                 *.env.example         allow

# Outside the workspace (shared storage) and runaway loops.
external_directory   *                     ask
doom_loop            *                     ask

# Shell commands that publish or destroy work.
bash                 git push*             ask
bash                 git reset --hard*     ask
bash                 git clean*            ask
bash                 git checkout -- *     ask
bash                 rm -rf *              ask
```

Examples you can add:

- `edit * ask` — approve every file change (you see the diff first).
- `bash * ask` then `bash ls*`, `bash cat*` … `allow` — approve every command
  except a few you trust.
- `web_fetch * deny` and `web_search * deny` — no internet for the agent.
- `web_search * ask` — approve every web search.
- `github_* ask` — approve every call to tools from the MCP server named
  `github` (MCP tools are named `<server>_<tool>`).
