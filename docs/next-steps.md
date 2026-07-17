# Next steps

Snapshot 2026-07-17. Ordered by priority. Cross-repo facts live in Claude's
project memory (`news-echo-status`); this file is the human-readable backlog.

## P0 — DIAGNOSED (2026-07-18): curator & essayist pick from different pools

**Verdict: case (B), a confirmed bug — not (A).** The essayist has *never* run
the judge (0 `JUDGE`/`ESSAY` rows in `llm_usage`; only `DIGEST`×266 and
`SELECT`×1). No essay was ever rejected — the essayist finds zero candidates
every day and returns silently.

Root cause — the two stages disagree on the candidate pool:

- **Curator** (`selectionCandidates`, `ItemRepository.kt:496`) selects from the
  **digest** pool ranked by `significance_score`, with `LEFT JOIN matches` —
  resonance is *optional*. So it shortlists the day's most significant news
  regardless of whether it resonated with any book.
- **Essayist** (`essayCandidates`, `ItemRepository.kt:404`) reads
  `shortlist JOIN matches` — an **INNER JOIN**, so a pick is only visible if it
  has a `matches` row.

Confirmed in prod: the single curator run (2026-07-16 21:05, 3 picks) shortlisted
3 items that are all `state=PUBLISHED` with **`match_rows = 0`**. The essayist's
INNER JOIN drops all three → no candidate → no judge call → no essay, forever.
ADR-009 (curator) and ADR-010 (resonance gate) were built separately and their
candidate pools were never reconciled.

Deployment is NOT the blocker for this: the running `ai-radar-digester` image was
built from HEAD `92ee99e` (image created 2026-07-16 08:14 UTC, ~1.5 min after
that commit) and has been up continuously — it contains the full essayist. The
essayist runs; it just never has a candidate.

Not a secondary bug: the single `selection_runs` row is fully explained. The
matcher+curator only went live ~2026-07-16; the curator's first run was
2026-07-16 21:05 UTC and the next is simply still pending (runs nightly at
`SELECT_HOUR_UTC`=21). At diagnosis time it was 2026-07-17 17:01 UTC, i.e.
before that night's 21:00 window — nothing missed. The daemon tick has been up
continuously since 2026-07-16 08:14 UTC.

Data now confirms the pool overlap is healthy going forward: every digest from
2026-07-17 onward has a `matches` row (10/10), because the matcher sits before
the digester (ADR-010) so post-gate digests always carry resonance evidence.
The 3 dead picks are pre-gate backlog (digested on/before 07-16, `match_rows=0`)
that the curator's `LEFT JOIN` let in.

Note: tonight's 21:00-UTC curator run may self-heal by accident — its window is
"digests since the last run (07-16 21:05)", which now contains only post-gate
(matched) items, so it could pick a consumable item and compose at 22:00 without
any code change. The fix below is still needed to make it *robust* (a downtime
gap that widens the window, or any future pre-gate item, would re-break it).

**FIXED (2026-07-18, this branch):** option (A) — `selectionCandidates` now
requires a `matches` row (`JOIN matches` instead of `LEFT JOIN`), so the
shortlist only ever holds picks the essayist can consume. Verified: build +
tests green; the new query dry-run against prod returns 7 consumable candidates
for tonight's window. Deploy note: the fix rides this branch, so it reaches prod
with the manual cutover (runbook §5) — or cherry-pick onto `main` to ship it
sooner via the old auto-deploy path. Rejected alternative (B): decouple the
essayist from a pre-existing `matches` row — bigger change, against ADR-010.

How to re-check (we have prod read access):
```
ssh nplus.space "docker exec -i ai-radar-postgres-1 psql -U airadar -d airadar"
```
Refs: ADR-009 (curator/shortlist), ADR-010 + amendment (resonance gate, judge).

## P1 — Theme-index experiment (DEFERRED: P0 turned out to be B, not A)

P0 was case (B), so this is no longer the immediate lever — fix the pool bug
first and let a real essay flow before re-testing discrimination. Kept for later.

Test whether an isolated theme vector discriminates genuine vs coincidence.
Everything is staged in `docs/experiments/theme-index/` (frozen 30-item news
sample + protocol). **Blocker: hand-label the 30** (`label-sheet.tsv`), then
build `book_theme_vectors` in book-library-hub and score vs the 0.20 baseline.
~$0.25 voyage, one-time. See that folder's README.

## P1 — Finish the bookshelf-echo rename cutover

GitHub repos already renamed: `ai-radar → bookshelf-echo`,
`ai-radar-site → bookshelf-echo-site`. Chosen approach: **full runtime
migration, done manually** (not a plain `git merge`). Full runbook:
`docs/runbooks/rename-cutover.md`.

- [x] **Site** — `rename/bookshelf-echo` merged to `main` and pushed
      (2026-07-17). GitHub Actions rebuilds Pages at `/bookshelf-echo-site/`.
- [ ] **Pipeline** — do NOT just merge to main: the branch changes the compose
      project name, so a plain deploy would create empty `bookshelf-echo_*`
      volumes and **wipe the Postgres DB + queue**. Must be a hand-run cutover
      on the deploy host with **volume migration** (`ai-radar_{pg,rabbitmq}-data
      → bookshelf-echo_*`, verify row counts, then merge + `docker compose up`).
      See runbook §5.
- [ ] Merge `Andrewnplus/nplus-infra` (prometheus targets) in lockstep with the
      pipeline container rename; reload Prometheus + fix Grafana `app=` label.
- [ ] nplus-backend LINE job: set env `AI_RADAR_DAILY_URL` /
      `AI_RADAR_ESSAY_URL` → `.../bookshelf-echo-site/...` (no code change).
- [ ] Optional later: rebrand the LINE card "📡 AI Radar" heading once that
      repo's WIP settles. Old `nplus.wiki/ai-radar-site/...` links will 404.

Deliberately NOT renamed: `airadar` DB/RabbitMQ identifiers, Kotlin package
`wiki.nplus.airadar`, `docs/adr/*`. (Compose project/volume/container names ARE
renamed under the full-migration choice — hence the volume migration above.)

## P2 — Product / positioning

- Source is now BBC world news (gh-trending dropped). Decide whether
  world-news × a broad shelf is the intended product, or whether the channels
  should be narrowed to raise the base rate of genuine resonance. This directly
  affects how often an essay can honestly publish (P0/P1).
