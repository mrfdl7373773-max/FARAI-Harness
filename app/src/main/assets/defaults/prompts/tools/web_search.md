Search the web. FARAI queries several search engines at once, straight from
the phone (DuckDuckGo, Brave, Bing, Yahoo and Wikipedia; Google News and Bing
News for `type: news`), and merges their rankings, so a page several engines
rank highly comes first. No API key is needed.

Parameters:
- `query` — what to search for. Write it like a search box query: the key
  terms, an exact error message in quotes, a library name plus version.
- `max_results` — optional, 5 to 25 (default from prompts/search.md, 8). Use more (15–25) for broad research or comparisons, fewer for a single fact.
- `type` — optional: `web` (default) or `news` for current events with dates.
- `time_range` — optional: `day`, `week`, `month` or `year`.
- `region` — optional `<country>-<lang>`, e.g. `id-id` for Indonesian
  results, `us-en` (default) for English.
- `include_domains` / `exclude_domains` — optional lists, e.g.
  `["developer.android.com", "kotlinlang.org"]` to stay on official docs.
- `page` — optional, for more results of the same query.
- `read` — optional, 0 to 25: also read the top N results in the same call,
  in parallel, and return their main text after the list. Defaults to 5 for
  `type: news` and 0 for `web`. Use 3–5 for a focused question, 10–25 for broad
  research or a news roundup (each page is cut shorter as N grows). Set 0
  when you only need links.

You get numbered results (title, URL, snippet, which engines found it, date
when known) and sometimes a short `Answer:` line. Snippets are fragments:
before you state a fact the user will act on (a version, a flag, an API
signature, a price, a date), read the page (`read` here, or `web_fetch` with several `urls` at once). Run a few
focused searches rather than one vague one, and run independent searches in
the same reply so they go in parallel. If a search returns nothing, reword it
or drop quotes; engines that block are rested automatically.

After reading, look for gaps before you answer. If the pages leave part of
the question open or contradict each other, run a new, sharper search (use
names, dates and terms from what you read) rather than answering with what
you have. A few rounds of search → read → check beat one big search.
