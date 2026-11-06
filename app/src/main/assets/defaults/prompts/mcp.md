# MCP servers

MCP (Model Context Protocol) servers give the agent extra tools: GitHub,
databases, browsers, documentation search, your own scripts. Each tool shows
up as `<server>_<tool>` and works like a built-in one, including the
permission rules (`github_* ask` in permissions.md, for example).

Only the `json` block below is read. Two layouts are accepted: FARAI's
`"mcp"` and the common `"mcpServers"`, so most configs can be pasted
unchanged. A project can also carry its own servers in
`.mcp.json`, `farai.json` or `.farai/mcp.json` in the workspace; those win
over this file when a name is the same.

- **Local** servers run inside Alpine Linux (install it in Settings →
  Linux), so `npx`, `uvx` and `python` servers work. Install the runtime
  once, e.g. `/linux add nodejs npm` or `/linux add uv`.
- **Remote** servers are reached over Streamable HTTP (`"type": "remote"`) or
  the older SSE transport (`"type": "sse"`). Put API keys in `headers`.
- **Sign-in (OAuth):** a remote server that needs an account shows
  "needs sign-in" in `/mcp`. Run `/mcp auth <name>`: the browser opens, you
  approve, and FARAI keeps and refreshes the token. `/mcp logout <name>`
  forgets it. Optional `"oauth": { "clientId": "…", "clientSecret": "…",
  "scope": "…", "callbackPort": 19876 }` for servers that need a registered
  app; `"oauth": false` when you put a token in `headers` instead.
- **Forms:** a server can ask you for details mid-task (elicitation). A form
  pops up; Decline or close it to say no.
- **Sampling:** a server can ask to run a prompt on your model. FARAI asks
  first (Allow / Always / Deny). `"sampling": true` allows it without asking,
  `"sampling": false` always declines.
- `"enabled": false` keeps a server in the file without starting it.
- `"timeout"`: milliseconds to wait for a slow server to start.

Servers start with your next message after a change. `/mcp` shows their
status, `/mcp add` adds one without editing this file, `/mcp tools` lists
what they offer.

```json
{
  "mcp": {}
}
```

## Examples (copy into the block above)

```text
"filesystem": {
  "type": "local",
  "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/workspace"]
},
"fetch": {
  "type": "local",
  "command": ["uvx", "mcp-server-fetch"]
},
"github": {
  "type": "remote",
  "url": "https://your-github-mcp-server/mcp/",
  "headers": { "Authorization": "Bearer YOUR_GITHUB_TOKEN" }
},
"context7": {
  "type": "remote",
  "url": "https://mcp.context7.com/mcp"
},
"memory": {
  "type": "local",
  "command": ["npx", "-y", "@modelcontextprotocol/server-memory"],
  "environment": { "MEMORY_FILE_PATH": "/workspace/.farai/memory.json" }
}
```

Paths inside local commands are the paths Linux sees: `/workspace` is the
app workspace and `/sdcard` is shared storage (when granted).
