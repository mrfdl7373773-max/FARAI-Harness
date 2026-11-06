List or read resources that connected MCP servers expose (documents, files,
database records, API objects).

Parameters:
- `action` — `list` to see every resource (server, URI, name, type), or
  `read` to fetch one.
- `server` — for `read`: the server name from the list.
- `uri` — for `read`: the resource URI exactly as listed.

Text comes back inline; binary content (images, PDFs) is saved under
`.farai/tool-output/` and its path is returned. Prefer a server's own tools
when they do what you need; use this for content a server publishes as
resources.
