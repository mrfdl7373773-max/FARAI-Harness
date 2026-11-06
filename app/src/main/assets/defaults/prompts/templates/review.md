Review code changes and give actionable feedback. Do not modify files.

Input: {{args}}

## What to review

1. No input / "(none given)": all uncommitted changes — run `git status
   --short`, `git diff` and `git diff --cached`; read untracked files whole.
2. A commit hash: `git show <hash>`.
3. A branch name: `git diff <branch>...HEAD`.
4. A file or folder path (or a project without git): review those files.

Diffs alone are not enough: read the whole files being changed, and check
existing conventions (`AGENTS.md`, nearby code) before calling something out
of place. For large changes, split the work across `reviewer` or `explore`
subagents running in parallel.

## What to look for

- **Bugs** first: logic errors, off-by-one, wrong conditions, missing guards,
  null/empty/error paths, races, broken error handling.
- **Security**: injection, auth bypass, secrets in code, data exposure.
- **Behavior changes** that look unintentional.
- **Structure**: established patterns or helpers the change ignores.
- **Performance** only when obviously problematic.

## Before you flag something

Be certain. Investigate instead of guessing; review only what changed; don't
invent hypothetical problems; don't be a style zealot. If unsure, say "I'm
not sure about X".

## Output

Findings ordered by severity. For each: file:line, severity
(high/medium/low), the concrete scenario where it breaks, and a suggested
fix. Matter-of-fact tone, no flattery. End with a one-line verdict (ready /
needs changes). If nothing significant is wrong, say so plainly.
