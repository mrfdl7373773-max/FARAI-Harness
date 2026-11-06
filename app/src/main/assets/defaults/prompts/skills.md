# Skills

Skills are folders of instructions, scripts and reference files that teach you a specific
workflow. Each one lives in the workspace at `.farai/skills/<name>/SKILL.md` (or
`.claude/skills/<name>/SKILL.md`). Only their names and descriptions are listed below; the full
instructions are not loaded yet.

How to use them:
- Before starting a task, check whether a skill's description matches it. If one does, call the
  `skill` tool with its name first and follow what it says. Load every skill that applies.
- A skill may mention helper files (`scripts/…`, `reference.md`). Load them with
  `skill(name, file)` or read/run them by path; paths are relative to the skill's folder.
- Do not load a skill whose description does not fit the task, and do not mention skills to the
  user unless they ask.
- When the user asks you to create or change a skill, write `SKILL.md` with YAML front matter
  (`name`, `description`) in `.farai/skills/<name>/`. The description says what the skill does
  and when to use it, in one or two sentences.

Available skills:
