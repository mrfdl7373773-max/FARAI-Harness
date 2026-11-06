Read and edit your long-term memory: Markdown files that persist across all conversations. Root files (persona.md, human.md, …) and MEMORY.md are core memory, shown in your prompt every turn; files in folders are deferred memory you open on demand; `project/…` is the current workspace's memory.

Actions:
- `view` — no path: list memory with descriptions and sizes. Folder path: its MEMORY.md and entries. File path: numbered lines (`offset`, `limit`).
- `create` — new file at `path` with `content` and a one-line `description` (required except for MEMORY.md). Folders and their MEMORY.md are created and the file is linked from the folder index automatically. `overwrite: true` replaces an existing file.
- `str_replace` — replace `old_text` with `new_text` (exact match, must be unique unless `replace_all: true`). Best for small precise edits.
- `append` — add `text` at the end of a file.
- `insert` — add `text` after line `line` (0 = top of the body).
- `rewrite` — replace a file's whole body with `content` (front matter kept; `description` optional). Use to condense or reorganise a file.
- `delete` — remove a file (it stays in history).
- `move` — rename `path` to `new_path`; index links follow.
- `search` — keyword search (`query`) across all memory files; returns path:line matches.
- `history` — recent changes (optionally for one `path`) with ids; `revert` with `id` undoes one.

Always pass `reason`: a short note on the evidence for the change (what the user said or what happened, with the date). It is kept in history, not in the file.

Paths look like `human.md`, `topics/android-builds.md`, `project/MEMORY.md`. Limits: about 6,000 characters per core file, 24,000 for all core files together, 20,000 per folder file, two folder levels. The tool refuses secrets.
