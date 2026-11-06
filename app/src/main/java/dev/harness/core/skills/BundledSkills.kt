package dev.harness.core.skills

/** Starter skills copied into .farai/skills on first launch. The user can edit or delete them. */
object BundledSkills {
    val all: Map<String, String> = mapOf(
        "skill-creator" to """
            |---
            |name: skill-creator
            |description: Create a new Agent Skill or improve an existing one. Use when the user asks to make, write, save, or edit a skill (SKILL.md), or to turn a workflow from this conversation into a reusable skill.
            |---
            |
            |# Skill creator
            |
            |A skill is a folder in the workspace: `.farai/skills/<name>/SKILL.md`, plus optional
            |helper files (scripts, references, templates) next to it.
            |
            |## Steps
            |1. Ask at most one or two questions if the purpose or trigger is unclear: what should the
            |   skill do, and when should it be used?
            |2. Pick a short kebab-case name (`pdf-report`, `android-release`).
            |3. Write `.farai/skills/<name>/SKILL.md`:
            |   ```markdown
            |   ---
            |   name: <name>
            |   description: <what it does>. Use when <the requests or files that should trigger it>.
            |   ---
            |
            |   # <Title>
            |
            |   ## Instructions
            |   Concrete, numbered steps. Name exact commands, file paths and checks.
            |
            |   ## Examples
            |   A request and what a good result looks like.
            |   ```
            |4. Keep SKILL.md focused (under ~300 lines). Move long reference material into
            |   `reference.md` and repeatable code into `scripts/`, and say in SKILL.md when to load or
            |   run each one.
            |5. The description is the only part the agent sees before loading the skill, so make it
            |   specific: what it does and when to use it, in one or two sentences.
            |6. Tell the user the skill's name and that it is active (it appears under /skills).
            |
            |## Improving a skill
            |Read the current SKILL.md, make the smallest change that fixes the problem the user
            |described, and keep the front matter valid.
            |""".trimMargin(),
    )
}
