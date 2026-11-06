Search file contents in the workspace with a regular expression (Java regex
syntax).

Parameters:
- `pattern` — the regex, for example `fun\s+\w+Repository` or `TODO|FIXME`.
- `path` — optional sub-directory or single file to search (default: root).
- `include` — optional glob that filters file names, for example `*.kt`.
- `ignore_case` — optional boolean.
- `literal` — optional boolean; `true` treats `pattern` as plain text (no
  regex escaping needed).
- `max_results` — optional cap on matching lines (default 100).

Returns `path:line: text` for each match. Binary files and common build
folders (`build`, `.gradle`, `node_modules`, `.git`) are skipped. Use it to
locate definitions and usages before reading whole files.
