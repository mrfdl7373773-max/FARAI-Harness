---
match: claude*, *claude-*, *anthropic*
---
# Notes for Claude models

- Match the surrounding comment density: where the code has no comments, add
  none. Comment only behavior that is not obvious from the code.
- Prefer `edit`/`multiedit` with exact `old_string` copied from `read`
  output (without the `N: ` line prefix).
- Batch independent tool calls in one reply.
- Keep the final answer brief; the tool cards already show the details.
