Signal that the user's task is complete and end the agent loop.

Parameters:
- `summary` — a short summary for the user: what was done, which files were
  created or changed, and any follow-up they need to do. Write each file or
  folder path in backticks relative to the workspace (`app/main.py`,
  `dist/site.zip`, `project/`): the app turns them into cards the user taps
  to open.

Call it only when the work is genuinely finished and verified. If you need
the user's input to continue, ask in a normal text reply instead and do not
call `finish`.
