Make several edits to one file in a single call. The edits apply in order,
each to the result of the previous one, and the file is written once: if any
edit fails, nothing is changed.

Parameters:
- `path` — the file.
- `edits` — a list of `{ "old_string", "new_string", "replace_all"? }`, with
  the same matching rules as `edit`.

Plan the order: a later edit must match the text as it is after the earlier
ones. To create a new file, make the first edit's `old_string` empty and put
the content in its `new_string`.

Prefer this over several `edit` calls on the same file: it is atomic and
faster.
