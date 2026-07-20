# Next steps

Snapshot 2026-07-20. Ordered by priority. Cross-repo facts live in Claude's
project memory (`news-echo-status`); this file is the human-readable backlog.

Prod read access:
```
ssh nplus.space "docker exec -i bookshelf-echo-postgres-1 psql -U airadar -d airadar"
```

## P0 — VERIFY TONIGHT: the essay path has not produced since 2026-07-17

`content/essays/` holds exactly one file, `2026-07-17.md`. Two nights produced
nothing, for two *different* reasons, and only the first is fixed:

- **07-18** — the critic gate (`58942b8`) booked `CRITIC`/`ESSAY_REVISE` into
  `llm_usage`, purposes no migration ever added. Every run died on the check
  constraint *after* paying for the pro-tier essay, wrote no `essays` row, and
  so re-ran on the next five-minute tick until the budget breaker tripped.
  **Fixed in `8499665`** — the gate is retired for a deterministic quote check
  (ADR-011), spend is metered in one place, and the daily jobs now cap their
  attempts. Deployed 2026-07-20 04:58 UTC.
- **07-19** — prod was simply down. The publisher's hourly snapshot heartbeat
  stops at 11:27 UTC and does not resume until 07-20 00:05 UTC, a window that
  contains the 22:00 essay hour, so that night's run almost certainly never
  happened. **Cause unknown and uninvestigated** — the last successful deploy
  before it was 07-19 10:20 UTC and the heartbeat survived that by an hour, so
  "the deploy broke it" does not fit. If it recurs, this is the real P0.

Open items:

- [ ] Tonight's 22:00-UTC run is the first real exercise of the new path
      (judge → essay → `QuoteVerifier` → save). The new failure mode to watch
      for is `unverified_quotes`: if the model will not quote in blockquotes, or
      quotes loosely, the day is forfeited and the *symptom is identical* to the
      bug just fixed. `docker logs --since 12h bookshelf-echo-digester | grep "essay 2026-07-20"`
- [ ] Reconcile the bill and confirm the 07-18 diagnosis: `ESSAY` rows in
      `llm_usage` for 07-18 with no matching `essays` row for that day.
- [ ] Decide whether to backfill 07-18/07-19. `essayExistsForDay` only looks at
      the current day, so past days are never retried; `ops republish-essay`
      re-renders an existing essay and cannot create one. Backfilling needs a
      new ops command. Not backfilling leaves two permanent gaps.
- [ ] `shortlist.pendingCount` was 7 at the last snapshot while
      `receivedLast24h` was 4 — picks are accumulating faster than news arrives.
      See P2.

Refs: ADR-009 (curator/shortlist), ADR-010 + amendment (resonance gate, judge),
ADR-011 (why the critic gate went away).

## Resolved — curator & essayist picked from different pools (2026-07-18)

`selectionCandidates` used `LEFT JOIN matches` while `essayCandidates` used an
INNER JOIN, so the curator could shortlist items the essayist was structurally
unable to consume — the reason zero essays had ever been published. Fixed in
`b69bf31` by requiring a `matches` row on the curator side too. ADR-009 and
ADR-010 had been built separately and their candidate pools never reconciled.

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
- [x] **Pipeline** — done 2026-07-18. Hand-run cutover on the deploy host:
      stopped `-p ai-radar`, copied `ai-radar_{pg,rabbitmq}-data →
      bookshelf-echo_*`, merged + pushed main, CI rebuilt images and deployed.
      Volume migration verified — no data loss (counts only rose from live
      traffic: items 719→724, matches 447→451; digests/essays/shortlist/usage
      unchanged). All 7 `bookshelf-echo-*` containers up. See runbook §5.
- [~] `Andrewnplus/nplus-infra`: scrape targets, the `matcher:9105` target that
      had been missing since the resonance gate shipped, LLM-cost-by-purpose and
      daily-job panels, and the dashboard rename (file + title + tags; **uid
      stays `ai-radar`** or every existing link breaks) are all merged and
      pushed as of `b7c5e35`. Host `git pull` + `docker restart infra-prometheus
      infra-grafana` still pending (needs andrew on host). Verify afterwards:
      Prometheus `/targets` shows five `bookshelf-echo` targets UP, and the
      by-purpose panel shows SELECT and ESSAY series.
- [x] nplus-backend LINE job: env set 2026-07-18 in `nplus-infra/backend.env`
      (host-only, gitignored), `docker compose up -d backend` → healthy,
      `ai_radar_daily_push [enabled]` 08:00 schedule loaded. NOTE: backend reads
      only `AI_RADAR_DAILY_URL` (`Env.aiRadarDailyUrl`, `Env.kt:89`);
      `AI_RADAR_ESSAY_URL` is a phantom — code never reads it, do NOT set it.
      Real failure mode was worse than first reported: a 404 on `daily.json`
      makes `AiRadarDigestFetcher.fetch()` fail so the WHOLE card fails to send
      (not "broken on click"); the footer link is `daily.pageUrl` from the
      payload (site-publisher writes it), not a backend env var.
- [ ] Optional later: rebrand the LINE card "📡 AI Radar" heading once that
      repo's WIP settles. Old `nplus.wiki/ai-radar-site/...` links will 404.
- [ ] Cleanup (after a few days' confidence): delete old `ai-radar_{pg,rabbitmq}
      -data` volumes and old ghcr `ai-radar-*` packages; fix the host git remote
      URL (still `ai-radar`, works via GitHub redirect).

Deliberately NOT renamed: `airadar` DB/RabbitMQ identifiers, Kotlin package
`wiki.nplus.airadar`, `docs/adr/*`. (Compose project/volume/container names ARE
renamed under the full-migration choice — hence the volume migration above.)

## P2 — Product / positioning

- Source is now BBC world news (gh-trending dropped). Decide whether
  world-news × a broad shelf is the intended product, or whether the channels
  should be narrowed to raise the base rate of genuine resonance. This directly
  affects how often an essay can honestly publish (P0/P1).
