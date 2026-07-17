# Next steps

Snapshot 2026-07-17. Ordered by priority. Cross-repo facts live in Claude's
project memory (`news-echo-status`); this file is the human-readable backlog.

## P0 — Diagnose why no essay ever publishes (the original product question)

The design faithfully implements the intended 4-step vision — valuable channels
→ filter to the truly valuable → resonate against the book library → deep
book-informed essay — but the site's **書櫃評析 section is empty**: no essay has
ever been published. Before optimizing signal quality, find out which case
we're in:

- **(A) the LLM relevance judge rejects every candidate, every day** → the
  system is working as designed (寧缺勿濫; world-news × a broad shelf is mostly
  coincidence). Then P1 (theme-index) is the right lever.
- **(B) the pipeline is stuck before the essay stage** → a bug to fix first.

How (we have prod read access):
```
ssh nplus.space "docker exec -i ai-radar-postgres-1 psql -U airadar -d airadar"
```
Check: is `selection_runs` firing daily? is `shortlist` being populated (and are
picks `composed_at`)? are there judge verdicts in `llm_usage` (by `purpose`)?
any rows in `essays` at all? are items reaching `DIGESTED`/`PUBLISHED` or piling
in `STALE`/`NO_RESONANCE`?
Refs: ADR-009 (curator/shortlist), ADR-010 + amendment (resonance gate, judge).

## P1 — Theme-index experiment (only if P0 = A)

Test whether an isolated theme vector discriminates genuine vs coincidence.
Everything is staged in `docs/experiments/theme-index/` (frozen 30-item news
sample + protocol). **Blocker: hand-label the 30** (`label-sheet.tsv`), then
build `book_theme_vectors` in book-library-hub and score vs the 0.20 baseline.
~$0.25 voyage, one-time. See that folder's README.

## P1 — Finish the bookshelf-echo rename cutover

GitHub repos already renamed: `ai-radar → bookshelf-echo`,
`ai-radar-site → bookshelf-echo-site`. Remaining (full runbook:
`docs/runbooks/rename-cutover.md`):

- [ ] Merge the three `rename/bookshelf-echo` branches (ai-radar,
      ai-radar-site, Andrewnplus/nplus-infra).
- [ ] Let CI rebuild `ghcr.io/nplus-father/bookshelf-echo-*` images.
- [ ] Deploy host: rename `~/workspace/{ai-radar,ai-radar-site}` dirs, update
      remotes, `docker compose down && up -d` (container/image names changed).
- [ ] Prometheus reload + update Grafana panels (`app="ai-radar"` →
      `"bookshelf-echo"`; metrics history keeps the old label — expected).
- [ ] nplus-backend LINE job: set env `AI_RADAR_DAILY_URL` /
      `AI_RADAR_ESSAY_URL` → `.../bookshelf-echo-site/...` (no code change).
- [ ] Optional later: rebrand the LINE card "📡 AI Radar" heading once that
      repo's WIP settles. Old `nplus.wiki/ai-radar-site/...` links will 404.

Deliberately NOT renamed: `airadar` DB/RabbitMQ identifiers, Kotlin package
`wiki.nplus.airadar`, `docs/adr/*`.

## P2 — Product / positioning

- Source is now BBC world news (gh-trending dropped). Decide whether
  world-news × a broad shelf is the intended product, or whether the channels
  should be narrowed to raise the base rate of genuine resonance. This directly
  affects how often an essay can honestly publish (P0/P1).
