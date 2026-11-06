Create or update `AGENTS.md` for the project in this workspace.

The goal is a compact instruction file that helps future sessions avoid
mistakes and ramp up fast. Every line should answer: "Would an agent likely
get this wrong without being told?" If not, leave it out.

Focus or constraints from the user (honor these): {{args}}

If the workspace holds several separate projects, ask which one (or write one
`AGENTS.md` inside each project folder).

## How to investigate

Read the highest-value sources first, in parallel where you can:
- `README*`, root manifests, workspace config, lockfiles
- build, test, lint, formatter, typecheck and codegen config
- CI workflows, pre-commit hooks, task runners (Makefile, package.json scripts)
- existing instruction files (`AGENTS.md`, `CLAUDE.md`, `.cursorrules`,
  `.github/copilot-instructions.md`)

If the architecture is still unclear, read a few files that show how the
system is wired together (entrypoints, routers, main modules), not random
leaf files. Prefer executable sources of truth over prose: when docs and
config disagree, trust the config and keep only what you can verify. Where
it is cheap and safe, run the build or test command once to confirm it works
on this phone.

## What to capture

- exact commands, especially non-obvious ones; how to run one test or one
  package; required order (lint → typecheck → test)
- package boundaries and the real entrypoints
- toolchain quirks: generated code, migrations, env loading, dev servers
- testing quirks: fixtures, required services, slow or flaky suites
- conventions that differ from language defaults
- anything specific to running here (Alpine under PRoot on Android): missing
  packages to `apk add`, ports, paths

## Writing rules

- Short sections and bullets. Simple repo → simple file.
- No generic advice, no long tutorials, no exhaustive file trees, nothing
  you could not verify.
- If `AGENTS.md` already exists, improve it in place: keep verified useful
  guidance, delete stale or fluffy claims.

Ask the user (with the `question` tool, one short batch at most) only about
things the repo cannot answer, like team conventions or release steps. When
done, show the final file path and a two-line summary of what it covers.
