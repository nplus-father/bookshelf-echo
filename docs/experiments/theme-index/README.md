# Experiment: theme-vector resonance signal

Does an **isolated theme vector** — embedding *only* each book's `📘 深度概覽`
block from its `_index.md` — discriminate genuine resonance from coincidence,
where the current raw book-vector distance does not?

Background: ADR-010 amendment. Absolute cosine distance measures library
density, not relatedness; the 1.10 gate is demoted to a trash filter and the
real gate is an LLM relevance judge. Any *new automatic* signal must first pass
a 30-label test (ADR-010 decision #3). This is that test.

Key finding from the 2026-07-17 investigation (book-library-hub): the 深度概覽
is **already inside** today's `book_vectors` (`scripts/embed-books.mjs`
`buildBookText`) but **diluted** with title / category / a flat list of every
chapter title. The experiment isolates the distilled text.

## Frozen sample (do not regenerate — reproducibility)

`label-sheet.tsv` and `experiment-queries.tsv`: **30 `news`-source matches**,
sampled deterministically from the 425 live matches on prod
(`ai-radar-postgres-1`) as of 2026-07-17.

Sample definition (deterministic, reproducible):
```sql
SELECT i.id
FROM matches m
JOIN items i ON i.id = m.item_id
JOIN item_contents ic ON ic.item_id = m.item_id
WHERE i.source = 'news'
  AND ic.extracted_text IS NOT NULL
  AND length(ic.extracted_text) > 200
ORDER BY md5(i.id::text)
LIMIT 30;
```
Pulled via `ssh nplus.space "docker exec -i ai-radar-postgres-1 psql -U airadar -d airadar ..."`.

### `label-sheet.tsv` — needs hand labels (the blocker)
Columns: `id | label_genuine_1_coincidence_0 | top_dist | margin | title |
top3_books | lead`. **Fill the `label` column: 1 = the bookshelf genuinely
frames the news, 0 = keyword/topic coincidence.** The original calibration's 30
labels were lost; this replaces them — keep it checked in this time.

### `experiment-queries.tsv` — the model input
Columns: `id | title | query_text_1500`. `query_text_1500` = `title + "\n" +
extracted_text`, truncated to 1500 chars — mirrors the matcher's live query
(`MATCH_QUERY_CHARS=1500`, `input_type=query`).

## Protocol (run in book-library-hub, on a scratch copy of library.db)

1. Label the 30 (above).
2. Build `book_theme_vectors`: regex-extract each book's block between
   `{{% details "📘 深度概覽" %}}` and `{{% /details %}}` (~1,500 chars,
   1,502 books), embed **that alone** (`voyage-3-large`, `input_type=document`).
   Reuse `VoyageClient` + the vec0/map helpers. ~$0.20–0.27, one-time.
   Optionally also a `theme+title` variant.
3. Embed the 30 `query_text_1500` and query `book_theme_vectors` (findBooks-style).
4. Score, per ADR-010: over the 30, genuine-vs-coincidence separation of
   (a) top-1 distance and (b) top1–top2 margin, then **precision at the best
   single cut**. Success = materially beats the 0.20 baseline *and* the raw
   index's ~0.40, with visibly separated distributions. Report raw vs
   theme-only vs theme+title side by side.

Outcome decides whether the theme vector becomes a pre-LLM gate (cutting judge
cost) or is "only" better context for the judge/essayist. A null result still
closes the door ADR-010 left ajar — either way it is worth the ~$0.25.

Caveat: if the failure is embedding-space density saturation, distilled vectors
may compress the space rather than separate it. The 30-label test answers this
cheaply before anything trusts the signal.
