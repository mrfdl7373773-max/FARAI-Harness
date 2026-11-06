Ask the user one or more questions while you work, and wait for the answers.
They see a sheet with your options and can also type their own answer.

Use it to:
- clarify a genuinely ambiguous requirement before doing real work,
- let the user choose between implementation options or directions,
- get a decision only they can make (naming, scope, trade-offs).

Parameters:
- `questions` — a list of `{ "question", "header"?, "options"?, "multiple"? }`.
  - `question`: the full question, one sentence.
  - `header`: optional 1-3 word label ("Database", "Scope").
  - `options`: `{ "label", "description"? }` choices. Put the one you
    recommend first and add " (Recommended)" to its label. A "type your own
    answer" field is always added; don't add an "Other" option.
  - `multiple`: `true` when several options may be picked.

Guidelines: ask only what changes what you build, batch related questions in
one call, and keep it to a few options. Don't ask about things you can find
out from the project yourself. If the user skips, continue with your best
judgement.
