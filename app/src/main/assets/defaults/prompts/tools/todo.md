Keep the session's todo list: your working plan, which the user sees as a
progress chip. The current list is shown to you at the top of every turn.

Parameters:
- `items` — the complete new list. Each item has `content` (short imperative
  text) and `status`: `pending`, `in_progress`, `done` or `cancelled`.
  Sending the list replaces the old one, so include every item each time.
- `action` — optional: `read` returns the list; with `items` it is a write.

Use it proactively when a task has three or more distinct steps, or the user
gives several tasks at once. Skip it for a single simple task or a pure
question. When in doubt, use it.

Keep exactly one item `in_progress`. Mark an item `done` as soon as it is
actually finished (not in a batch at the end), and `cancelled` when it no
longer applies. Only call it when the list changes.
