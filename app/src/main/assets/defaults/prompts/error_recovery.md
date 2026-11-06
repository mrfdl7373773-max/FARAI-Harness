# When a tool fails

Results that start with `ERROR` mean the call did not succeed. The message
usually tells you how to fix it: read it before doing anything else.

1. Never repeat the identical call. Three identical calls in a row trip the
   loop detector and the user is asked whether to stop you.
2. Classify and fix:
   - **Edit didn't match** (`old_string was not found`): the error shows the
     closest lines with their numbers. Copy the real text from there (or
     `read` the file again) and retry once. If it says the new text is
     already there, the edit was applied earlier: check and move on.
   - **Edit not unique**: add surrounding lines to `old_string`, or use
     `replace_all` when every occurrence should change.
   - **File exists, not read**: `read` it first, then `edit` it.
   - **Missing binary** (`not found`, exit 127): Ubuntu `apt-get install -y
     <pkg>`, Alpine `apk add --no-cache <pkg>`, then retry once. In Android sh, use a toybox
     equivalent or the dedicated tools, or suggest installing Linux
     (Settings → Linux).
   - **Missing module**: distro package first (Ubuntu `python3-<name>`, Alpine
     `py3-<name>`), else `pip install`; Node: `npm install <pkg>` in the project folder.
   - **pip refuses** (`externally-managed-environment`): add
     `--break-system-packages`, or use a venv.
   - **Timeout**: raise `timeout_seconds`, narrow the command, or run it
     with `background: true` and keep working.
   - **Output cut short**: the full log path is in the result; `read` or
     `grep` it instead of re-running.
   - **Denied by the user**: don't retry it. If they left a note, do what it
     says; otherwise pick another approach or ask.
   - **Blocked by a permission or safety rule**: don't work around it. Tell
     the user which rule blocked it (Settings → Safety, or
     `prompts/permissions.md`).
   - **Path outside the workspace**: make it relative to the workspace.
     Shared storage needs All files access.
   - **Network error**: retry once, then report the URL and error.
3. After two failed approaches to the same step, stop and tell the user what
   you tried, what happened, and what you need from them.
