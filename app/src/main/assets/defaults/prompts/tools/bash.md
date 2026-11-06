Run a shell command. Check the `shell:` line of the environment block to see
where it runs, and follow the matching section below.

## When the shell is Ubuntu or Alpine Linux (PRoot)

The command runs with `/bin/sh -c` inside the Linux named on the `shell:` line
(Ubuntu: glibc, `apt`, `/bin/sh` is dash and bash exists; Alpine: musl, `apk`,
busybox), as a fake root user.

- The working directory is `/workspace` (the app workspace). Shared storage is
  at `/sdcard` when the user granted All files access.
- pip works directly; PIP_BREAK_SYSTEM_PACKAGES is set. For bigger projects
  prefer `python3 -m venv .venv`.
- `bash` is available when installed; otherwise the shell is busybox ash.
- There is no systemd, no real root, no kernel modules, and no raw sockets
  (ping may fail). Long-lived servers stop when the command times out.
- Paths given to `read`, `write`, `edit` may use the same `/workspace/...` and
  `/sdcard/...` forms.

### Missing dependencies: install them yourself

You (and every subagent) install whatever a task needs without asking the
user first. Never stop to tell the user "please install X"; install it, then
continue.

1. Check before installing: `command -v <tool>`, `python3 -c "import <mod>"`,
   `node -e "require('<pkg>')"`, or the project's lockfile.
2. Read the signal and pick the installer:
   - exit 127, `not found` → a program.
     Ubuntu: `apt-get install -y --no-install-recommends <pkg>` (run
     `apt-get update` first if it says "Unable to locate package"; find names
     with `apt-cache search <name>`; e.g. git, curl, nodejs npm,
     build-essential).
     Alpine: `apk add --no-cache <pkg>` (`apk search <name>`; e.g. git, curl,
     nodejs npm, build-base, bash).
   - `ModuleNotFoundError` / `No module named X` → Python: try the distro
     package first (Ubuntu `python3-<x>`, Alpine `py3-<x>`: pytest, requests,
     numpy); otherwise `pip install <x>`. When a `.venv` exists, use
     `.venv/bin/pip`.
   - `Cannot find module` → Node: `npm install <pkg>` in the project folder.
   - A project file lists dependencies (`requirements.txt`, `pyproject.toml`,
     `package.json`) → install from it once: `pip install -r requirements.txt`,
     `pip install -e .`, `npm install`.
   - Compiler or header errors (`gcc: not found`, `Python.h`) → Ubuntu:
     `apt-get install -y build-essential python3-dev`; Alpine: `apk add
     --no-cache build-base python3-dev` (add `linux-headers`, `libffi-dev`,
     `openssl-dev` when the error names them).
   - A prebuilt binary fails on Alpine with `not found` although the file
     exists (it was built for glibc): prefer a musl build, `apk add gcompat`,
     or tell the user Ubuntu runs it as is.
3. Run the original command again once. If the same install fails twice
   (network, no such package), stop and tell the user the exact error.
4. Install only what the task needs, quietly (`-q`, `--no-cache`), and mention
   in your final answer what you installed.
5. A `ModuleNotFoundError` for a module that lives in the project itself (a
   local file like `calc.py`) is not a missing package: fix the working
   directory or `PYTHONPATH` instead of installing.
6. If a safety rule blocks or holds the install, do not work around it.

## When the shell is Android /system/bin/sh

This is Android, not a Linux distribution. The userland is toybox: `ls`,
`cat`, `cp`, `mv`, `rm`, `mkdir`, `grep`, `sed`, `find`, `wc`, `sort`, `head`,
`tail`, `tar`, `gzip`, `base64`, `sha256sum` usually exist. There is NO
python, git, node, curl or package manager, so nothing can be installed here.
Tell the user they can install Linux (Ubuntu recommended, or Alpine) from
Settings → Linux (or `/linux install ubuntu`) if the task needs them.

## Parameters

- `command` — the command line. Quote paths with spaces.
- `description` — optional, 5-10 words saying what it does ("Run unit
  tests"); shown to the user on the tool card.
- `workdir` — optional folder to run in (workspace-relative). Use it instead
  of `cd dir && …`.
- `timeout_seconds` — optional, default from Settings, max 1800. A command
  that runs out of time is killed.
- `background` — optional `true` for dev servers, watchers and long builds:
  it returns at once with a job id and a log path. You are told when it
  finishes (at the start of a later turn). Don't poll in a loop; keep working,
  or call `job` with `action: "wait"` when you need the result, `"output"` to
  peek, `"kill"` to stop it.
- Localhost servers (a web app, a game, an API the user wants to open in their
  browser): always start them with `background: true`. They keep running after
  your turn ends and while the app is in the background (a notification shows
  them), until the user asks you to stop or remove the server; only then call
  `job` with `action: "kill"`. Never kill a server just because your task is
  done. Bind to `0.0.0.0` or `127.0.0.1`, check it answers (for example
  `curl -sI http://127.0.0.1:PORT`), and give the user the link
  `http://localhost:PORT`.

## Always

- Each call is a fresh shell: `cd` and exported variables do not persist.
- Environment variables containing KEY, TOKEN, SECRET, PASS or CRED are never
  passed in.
- Commands go through permission rules and the Safety lists; some wait for
  the user's approval, and a denial may carry a note telling you what to do
  instead.
- Large output is shortened to its beginning and its end, and the full output
  is saved to a log file whose path is in the result: `read` or `grep` that
  file instead of running the command again.
- Pass non-interactive flags (`-y`, `--no-input`, `CI=1`) so nothing waits
  for keyboard input. Avoid chaining unrelated commands with `echo "---";`
  separators; make separate calls (they can run in one reply).

Prefer the dedicated `read`, `write`, `edit`, `glob` and `grep` tools for
file work: they are faster and give cleaner output than shell equivalents.
