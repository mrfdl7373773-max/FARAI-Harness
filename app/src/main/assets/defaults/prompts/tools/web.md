Read web pages. Give one `url`, or up to 25 in `urls`: they are fetched
together (12 at a time), each with a 12-second limit, so reading many pages takes
about as long as reading the slowest one. Always batch the pages you need into
one call instead of fetching them one by one.

Parameters:
- `url` — one absolute http(s) URL.
- `urls` — a list of up to 25 URLs, read in parallel.
- `format` — optional:
  - `article` (default): only the main content (the article, the docs page,
    the README), as Markdown, without menus, footers, ads or "related" boxes.
  - `full`: the whole page as Markdown, with links as `[text](url)`. Use it
    for index pages, link lists and navigation.
  - `text`: the whole page as plain text.
  - `html`: raw HTML.
- `max_chars` — optional cap per page (default 20000 for one page, 6000 each
  for 2–3, 4000 each up to 10, 2500 each beyond that).

Each page comes back as `# title`, `URL:` (the real address after
redirects), a line with site, publish date and word count, then the text.
Google News, Bing News and DuckDuckGo redirect links are resolved to the
publisher's page automatically. Pages that need JavaScript or block bots are
read through a reader service. JSON and plain text come back as they are.
It cannot log in. If a page fails, take another source from your search
results rather than retrying the same URL.
