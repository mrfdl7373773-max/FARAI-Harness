Replace text in an existing file by exact match.

Parameters:
- `path` — the file to edit.
- `old_string` — the text to find. Copy it from `read` output, keeping the
  exact indentation (tabs or spaces) and WITHOUT the `N: ` line prefix.
- `new_string` — the replacement (must differ from `old_string`).
- `replace_all` — optional. By default `old_string` must match exactly one
  place; set `true` to change every occurrence (renaming a variable).

Matching is forgiving in safe ways: if there is no exact match it retries
ignoring curly quotes / dashes / odd spaces, then trailing whitespace, then
indentation (re-indenting your replacement to fit) — the last tiers only when
the match is unique. CRLF files keep their line endings.

When it fails, the error shows the closest lines in the file with their
numbers, or tells you the new text is already there: use that to fix the
next attempt instead of re-reading. Several matches → add surrounding lines
to make `old_string` unique.

The result is a unified diff of the change. For several changes to one file
use `multiedit`; for changes across files `patch` is also available. Use
`write` to create files.
