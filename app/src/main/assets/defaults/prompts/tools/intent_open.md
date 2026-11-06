Open a URL or app on the device with an Android `ACTION_VIEW` intent.

Parameters:
- `uri` — an `https://`, `http://`, `mailto:`, `tel:`, `geo:` or app deep-link
  URI.

Use it when the user asks you to open a page or app for them. It returns
whether an app accepted the intent. It cannot read anything back from the
opened app.
