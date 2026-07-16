# ADR-010: A pre-LLM resonance gate against the book library

- Status: Accepted
- Date: 2026-07-16

## Context

Ten days of live output showed the content selection was broken: under the
daily digest cap, whoever entered the queue first ate the quota (two days were
10/10 gh-trending), and the LLM's own significance scores had no
discrimination (nearly everything scored 4/5) — and by the time a score
exists, the money is already spent. The system lacked an importance signal
computable *before* any LLM call.

The news-echo plan (book-library-hub `docs/news-echo-plan.md`) supplies one:
**vector similarity between the news and the owner's book library** — "how
much does my bookshelf have to say about this?" — personal by construction,
and costing only one Voyage query embedding (cents per day). A Phase 0 spike
(`docs/news-echo-spike-report.md` there) validated retrieval quality (5/5 news
items had genuinely resonant passages) and calibrated the thresholds.

## Decision

A new `matcher` stage sits between the enricher and the digester
(`match.q`): it queries library-bridge `POST /search` (title + article lead,
no translation — the spike showed cross-language retrieval is lossless) and
gates on the **raw cosine distance of the nearest book**:

- distance > `MATCH_NO_RESONANCE_DISTANCE` (default 1.10) → `NO_RESONANCE`,
  terminal. No LLM money is ever spent on the item.
- otherwise → `MATCHED`, and the evidence (books + passages with raw
  distances) is stored in `matches` and travels with the item: the curator
  sees it when ranking, the essayist quotes from it.

Thresholds come from the spike: strong resonance ≈ 1.03, clear non-resonance ≈
1.10. RRF fusion scores are rank-only transforms and are never used for
thresholds.

## Consequences

- The cost funnel becomes: fetch (free) → resonance gate (cents) → per-item
  digest (cheap model) → daily selection + essay (strong model, once a day).
  Each tier only sees what the cheaper tier passed.
- The matcher spends no LLM budget, so it may safely run as its own process —
  the single-LLM-spender constraint (ADR-009) is untouched; the essayist runs
  inside the digester with the curator.
- One more hop before digestion; retry ladder and idempotent-consumer
  semantics apply unchanged (`match.q` gets its own retry tiers for free).
- `NO_RESONANCE` is data, not garbage: state counts land in the metrics
  snapshot, and stored distances let the threshold be recalibrated from live
  traffic.
- Migration: items already ENRICHED keep their parked digest.q messages; the
  digester no-ops them (state guard is now MATCHED) and `ops redrive --apply`
  re-routes ENRICHED → match.q.

## Amendment (2026-07-16, same day): the gate is a trash filter, not the gatekeeper

The first 281 live matches falsified the spike's central assumption. Absolute
cosine distance does **not** measure "does my bookshelf have something to say
about this" — it measures how dense the library is around that topic. Evidence
(full analysis: book-library-hub `docs/news-echo-spike-report.md`):

- Live queries (title + 1500 chars) sit far lower than the spike's short
  queries: median 0.89, so the 1.10 threshold rejected only 2.5%.
- The *strongest* resonances were gh-trending AI-handbook slop (0.739–0.781),
  beating every real news item — 1405 books with saturated AI coverage put
  noise vectors in the middle of the embedding space, near everything.
- A 30-item hand-labelled sample (6 genuine, 24 coincidence) showed neither
  distance (0.884 vs 0.887) nor top1–top2 margin (0.022 vs 0.017) separates
  the two at all.

Decisions taken:

1. **Do not tune the threshold.** No cutoff separates real from coincidence,
   and any value fitted here would be fitted to noise and die with the next
   batch of books. `MATCH_NO_RESONANCE_DISTANCE` stays at 1.10, demoted to a
   coarse trash filter.
2. **The real gate is an LLM relevance judge** (`EssayistJob`, own cheap tier
   `JUDGE_MODEL` — it must not inherit `GEMINI_MODEL`, which prod sets to pro):
   after the curator picks and before the essayist spends, each candidate gets
   a verdict on whether the passages genuinely frame the news. At most
   `ESSAY_JUDGE_MAX_CANDIDATES` (3) verdicts/day, cents in cost. Nothing
   survives → no essay that day, which was always a legal outcome. Judged-down
   picks are consumed, so a dead pairing is never retried.
3. **Only labelled data may justify a new automatic signal.** The spike's 1.10
   came from 5 hand-picked cases; the margin hypothesis died the moment it met
   30 labels. Any future signal (chapter-level distance, multi-book voting, a
   reranker) gets the same 30-label test first.

This trades a cheap-but-useless gate for a slightly-costlier-but-effective one.
The failure mode it prevents is not overspending: it is publishing an essay
that earnestly argues a resonance that does not exist.
