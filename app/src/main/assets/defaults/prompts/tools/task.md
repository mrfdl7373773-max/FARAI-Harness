Start a subagent: a separate agent with its own fresh context that works on
one task and returns a single report to you.

Parameters:
- `description` — a 3 to 5 word label shown to the user.
- `prompt` — the complete task. The subagent sees none of your conversation:
  include every path, name, constraint and decision it needs, say what it may
  or may not change, and what form the answer should take.
- `agent` — which agent runs it (see the list below). `explore` for finding
  and understanding code (say "quick", "medium" or "very thorough"),
  `general` for multi-step work that may change files, `reviewer` for a code
  review. Defaults to `general`.
- `task_id` — optional; pass the id from an earlier result to send that same
  subagent a follow-up with its context intact.

When to use it:
- Wide searches whose intermediate output you don't need ("find every call
  site of X and how each uses it").
- Independent pieces of work that can run at the same time: put several
  `task` calls in one reply and they run in parallel. Give them
  non-overlapping files.

When not to: reading one known file, a single grep, or anything you need to
watch step by step. The subagent's report is not shown to the user, so
summarize what matters in your own reply.
