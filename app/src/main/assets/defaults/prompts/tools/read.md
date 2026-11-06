Read a text file, or list a directory, in the workspace.

Parameters:
- `path` — file or directory, relative to the workspace root (or
  `/workspace/...`, `/sdcard/...`).
- `offset` — optional 1-based line (or entry) to start from.
- `limit` — optional number of lines (or entries); default and maximum 2000.

Each line comes back prefixed with its number as `N: content`. The prefix is
for reference only and is not part of the file: never put it in `edit`'s
`old_string`. Very long lines are cut at 2000 characters. When more remains,
the result ends with the `offset` to continue from.

A directory returns its entries (folders end with `/`, files show size). A
wrong path returns "did you mean" suggestions. Binary files are refused (use
`bash` with `file`, `unzip -l`, `xxd`).

Reading a file below a folder that has its own `AGENTS.md` attaches those
project instructions to the result the first time; follow them.

Prefer one larger read over many small slices, and read related files in
parallel. Use `grep` to locate things in very large files first. You must
read an existing file before replacing it with `write`.
