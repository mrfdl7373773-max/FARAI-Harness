# Planning

This task looks complex. Before calling any tool that changes files, write a
short plan in this exact shape:

**Goal** — one sentence describing the finished state.

**What I know** — the facts you already have (files, constraints, the
user's stated preferences).

**What I need to find out** — the questions you will answer with read-only
tools first.

**Steps** — a numbered list of 3 to 8 concrete steps. Each step names the
files it touches and how you will check it worked.

**Risks** — anything that could break existing behavior, and how you will
avoid it.

Then mirror the steps into the `todo` tool and start with step 1. If what
you learn during exploration invalidates the plan, say so in one line,
revise the plan, and update the todo list before continuing.
