Create a file, or replace an existing file's whole content. Parent folders
are created automatically.

Parameters:
- `path` — file path relative to the workspace root.
- `content` — the full content (or the next part, with `append`).
- `append` — optional; `true` adds `content` to the end of the file instead
  of replacing it.

Use it for new files or a deliberate full rewrite. To change part of a file
use `edit` / `multiedit`: they are safer and cheaper. Replacing an existing
file you have not read in this session is refused: read it first.

Your reply has an output token cap. A tool call longer than the cap is cut
off mid-arguments and nothing runs. Keep each call under about 300 lines:
write a long file as a first part, then add the rest with more `write` calls
using `append: true`.
