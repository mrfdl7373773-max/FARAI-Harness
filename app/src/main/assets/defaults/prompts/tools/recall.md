Search everything said in past conversations with the user (all sessions, including messages that were compacted away). Use it when the user refers to something from before ("like last time", "the bug we fixed", "what did I say about…"), when you meet a name or decision you don't recognise, or before asking the user to repeat themselves.

- `query`: keywords (any language). Results are ranked; each shows `session`, `around` (message number), the session title, who said it and when.
- `after` / `before`: limit by date (e.g. "2026-11-01" or "5 November 2026").
- `role`: only `user` or only `assistant` messages.
- `session`: search within one session.
- Expand a result: pass `session` and `around` (plus `before_count` / `after_count`, default 4) to read the messages around it.
- `include_current: true` also searches the current session (normally skipped because you already see it).
- `limit`: number of results (default 8, max 25).

Strategy: search with 2–4 distinctive words, open the best hit with `around`, and refine with dates if needed. What you learn that will matter again belongs in memory (memory tool), not just in your answer.
