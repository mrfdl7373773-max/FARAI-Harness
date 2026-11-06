# Welcome to FARAI Harness

FARAI Harness is a coding agent that runs on your phone. It talks to any
OpenAI-compatible API (OpenAI, OpenRouter, Groq, Together, a local server on
your network, and others) and works inside a private workspace folder that
belongs to this app.

## What makes it different

**Every instruction the model receives is yours to edit.** Its identity, its
working style, the description of every tool, how it summarises long
sessions, how it plans and checks its work: each one is a plain markdown
file under **Settings → Prompts**. Change one, save it, and the very next
message uses the new text. Each file keeps a history of your last 50 saves,
and can be reset to the shipped default at any time.

## Getting started

1. Tap **Configure Provider** and enter a base URL, an API key and a model
   name. Use **Test Connection** to check them.
2. Start a session with the **+** button.
3. Ask for something concrete: "Create a Python script that parses a CSV
   and prints the column averages", or "Explain what's in the workspace".
4. Type `/` in the chat box to see every command, or `/help` for the full
   list.

## Good to know

- The agent's tools only see the app's workspace folder.
- Commands the agent runs go through the allowlist and blocklist in
  **Settings → Safety**. You decide what they contain.
- Your API key is stored encrypted on this device and never written to
  exported files.
