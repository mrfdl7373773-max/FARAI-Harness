# Web search

How `web_search` searches. FARAI has its own small search engine built in: it asks
several public search engines at once from your phone and merges their
results. No account, no API key, no server.

Only the code block below is read. Every key is optional.

```json
{
  "engines": ["duckduckgo", "brave", "bing", "yahoo", "wikipedia"],
  "news_engines": ["google_news", "bing_news"],
  "region": "us-en",
  "safesearch": "moderate",
  "max_results": 8,
  "timeout_seconds": 8,
  "mode": "full",
  "answer": true,
  "cache_minutes": 10,

  "endpoint": "",
  "endpoint_type": "json",
  "api_key": "",
  "fallback": true
}
```

- **engines**: web engines to ask. Available: `duckduckgo`, `brave`, `bing`,
  `yahoo`, `wikipedia`, `mojeek` (strict about bots, off by default).
- **news_engines**: used for `type: news`. `google_news`, `bing_news`.
- **max_results**: results per search when the agent does not say, 5 to 25 (default 8).
- **region**: default `<country>-<lang>` (`id-id` for Indonesian results).
  The agent can still pick a region per search.
- **safesearch**: `off`, `moderate` or `strict`.
- **mode**: `full` waits for every engine and merges them (best ranking);
  `fast` answers as soon as one engine has enough results.
- **answer**: add a short instant answer (DuckDuckGo, else Wikipedia).
- **cache_minutes**: repeat searches within this time are answered from
  memory. `0` turns the cache off.
- An engine that shows a CAPTCHA or rate-limits rests for a few minutes and
  the others keep answering.

Your own server (optional): set **endpoint** to use it instead of the
built-in engines.

- `endpoint_type: "json"` — any server that answers `GET /search?q=…` with
  `{"results": [{"title", "url", "content"}]}`, e.g.
  `"endpoint": "http://192.168.1.5:8000"`.
- `"searxng"` — a SearXNG instance with JSON output enabled.
- `"tavily"` — `https://api.tavily.com` with your key in **api_key**.

With **fallback** `true`, FARAI uses the built-in engines whenever the
endpoint fails.
