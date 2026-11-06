Find files in the workspace whose paths match a glob pattern.

Parameters:
- `pattern` — a glob such as `**/*.kt`, `src/**/test_*.py`, or `*.md`.
  `**` matches any number of directories, `*` matches within one path
  segment, `?` matches one character, `{a,b}` matches alternatives.
- `path` — optional sub-directory to search in (default: workspace root).

Returns matching paths relative to the workspace root, newest first, capped
at 200 results. Use it to discover project structure before reading files.
