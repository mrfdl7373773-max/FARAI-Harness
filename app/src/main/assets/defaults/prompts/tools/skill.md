Load an Agent Skill: returns the full instructions from its SKILL.md plus the list of helper
files in its folder. The available skills and their descriptions are listed in the system
prompt under "Skills".

Parameters:
- `name` — the skill's name, exactly as listed.
- `file` — optional: a helper file inside the skill folder to load instead, by relative path
  (e.g. `reference.md`, `scripts/build.py`).

Call it before working on a task a skill's description matches, then follow its instructions.
Scripts in the skill folder can be run with `bash` using the folder path the result gives.
