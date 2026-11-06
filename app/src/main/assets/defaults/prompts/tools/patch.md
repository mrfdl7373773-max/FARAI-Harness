Apply a patch that adds, updates, moves or deletes one or more files. Every
hunk is checked first; if any fails, nothing is written.

Parameter:
- `patch` — the full patch text:

```
*** Begin Patch
*** Add File: hello.txt
+Hello world
*** Update File: src/app.py
*** Move to: src/main.py
@@ def greet():
-print("Hi")
+print("Hello, world!")
*** Delete File: obsolete.txt
*** End Patch
```

Rules:
- Each file section starts with `*** Add File:`, `*** Update File:` or
  `*** Delete File:`, followed by a workspace-relative path.
- In an added file every line starts with `+`.
- In an update, hunks start with `@@` (optionally followed by a line that
  anchors the hunk, such as a function signature). Inside a hunk, lines start
  with ` ` (context), `-` (remove) or `+` (add). Include about three lines of
  context around each change so the hunk can be found.
- `*** Move to:` right after `*** Update File:` renames the file.
- Adding over an existing file you have not read is refused.

Use it for coordinated changes across several files. For one small change,
`edit` is simpler.
