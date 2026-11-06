Look after commands started with `bash` and `background: true`.

Parameters:
- `action` — `list` (jobs of this session), `output` (latest output of one
  job), `wait` (block until it finishes or `timeout_seconds` passes, then show
  the output), or `kill` (stop it).
- `id` — the job number, for `output`, `wait` and `kill`.
- `timeout_seconds` — for `wait`; default 120, max 1800.
- `max_chars` — how much of the log tail to return (default 6000).

Finished jobs are announced automatically at the start of your next turn, so
you rarely need this. Use `wait` only when your next step depends on the
result and there is nothing else useful to do. Don't call `output` in a loop.
Stop servers you started when the task no longer needs them.
