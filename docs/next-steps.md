# Next steps

Snapshot 2026-08-04. Ordered by priority. Cross-repo facts live in Claude's
project memory (`news-echo-status`); this file is the human-readable backlog.

Prod read access (Postgres moved into CNPG on k3s, 2026-07-21):
```
ssh nplus.space "kubectl exec -n bookshelf-pg bookshelf-pg-1 -- psql -U postgres -d airadar"
```

## Resolved — the essay path has produced daily since 2026-07-20

The critic-gate bug below was fixed in `8499665` (ADR-011) and the pool
mismatch in `b69bf31`. Fourteen essays in the fifteen days to 08-04, one
legitimately blank day. Daily spend sits at $0.30–0.38, of which DIGEST is
~$0.18 (10 items/day). Kept below because the *shape* of the failure — paying
for a pro-tier call and then dying on a check constraint, silently, for two
days — is the one worth remembering.

## P1 — The resonance gate is a trash filter, and the code now says so

Confirmed on 08-04 against 661 live matches: the 1.10 cutoff rejected 8 items
(1.2%), distance median 0.93. ADR-010's amendment had already concluded this;
`446f26d` renamed the stage to what it actually is (evidence, not gate) and
moved the freshness check ahead of it, so items the digester would drop as
STALE no longer buy a Voyage query embedding and a `matches` row first — 452
STALE against 437 PUBLISHED said nearly half the traffic was doing exactly
that.

What is still open: **the digest tier is where the money goes and nothing
narrows it.** Ten items a day get digested because ten is the cap, not because
ten are worth it. The theme-index experiment below is the standing proposal for
a pre-LLM signal that could; it needs 30 hand labels first.

## P1 — library-bridge was serving empty chapters for two days (2026-08-02→04)

Found by audit, not by an alert. `~/workspace/books` became a symlink to
`/mnt/data/books` on 08-02 23:52; the pod was not restarted, so it kept the
emptied old directory mounted and `/chapter` returned 200 + empty string for
every chapter id. The essayist silently fell back from 12,000-char chapter
excerpts to 200-char passage snippets — 08-03's essay is the one written that
way. Fixed by `nplus-gitops 00afe94` (mount the real path) plus a rollout.

Guarding it now: `library_corpus_readable_ratio` and two more index-health
gauges from the bridge, with rules in
`nplus-gitops/workloads/monitoring-rules/library-bridge-alerts.yaml`. The
library index had also been frozen since 07-15 while the corpus kept syncing;
that is now a nightly cron in book-library-hub.

## Historical — the two-day essay outage of 2026-07-18/19

`essays` holds exactly one row, 2026-07-17. Both blank nights have the same
cause, confirmed against prod on 2026-07-20:

| day   | ESSAY calls | ESSAY cost | day total | essays row |
|-------|-------------|-----------|-----------|------------|
| 07-17 | 1           | $0.0257   | ~$0.10    | yes        |
| 07-18 | 11          | $0.2364   | $0.31     | none       |
| 07-19 | 11          | $0.2426   | $0.32     | none       |

The critic gate (`58942b8`) booked `CRITIC`/`ESSAY_REVISE` into `llm_usage`,
purposes no migration ever added — both have **zero rows**, so those inserts
never once succeeded. Every run died on the check constraint *after* paying for
the pro-tier essay, wrote no `essays` row, and so re-ran on the next five-minute
tick until the daily budget breaker stopped it at ~$0.31. Normal nights cost
~$0.09; the overspend is ~$0.44 across the two, plus 11 unrecorded critic calls
a night.

**Fixed in `8499665`** — the gate is retired for a deterministic quote check
(ADR-011), spend is metered in one place, and the daily jobs cap their attempts.
Deployed 2026-07-20 04:58 UTC.

Separately and still unexplained: the **publisher** stopped writing its hourly
snapshot from 07-19 11:27 UTC to 07-20 00:05 UTC. The digester was healthy
throughout — it made all 11 essay calls that night — so this is a
publisher-only fault, not a stack outage. Nothing was lost (no essay existed to
publish) but the cause is unknown. Do not read publisher silence as pipeline
silence again.

Open items:

- [x] The new path (judge → essay → `QuoteVerifier` → save) has run daily since
      07-20 without a repeat. `unverified_quotes` has not forfeited a day.
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

## P1 — Theme-index experiment (blocked on 30 hand labels — Andrew's to clear)

The pool bug is fixed and essays have flowed daily for two weeks, so the reason
this was deferred is gone. It is now the only proposal on the table for
narrowing the digest tier before the money is spent.

Test whether an isolated theme vector discriminates genuine vs coincidence.
Everything is staged in `docs/experiments/theme-index/` (frozen 30-item news
sample + protocol; verified 08-04 — the two TSVs agree on all 30 ids and all 30
still resolve in prod). Then build `book_theme_vectors` in book-library-hub and
score vs the 0.20 baseline. ~$0.25 voyage, one-time.

**Blocker: hand-label the 30** in `label-sheet.tsv` (`1` = the bookshelf
genuinely frames the news, `0` = keyword coincidence). This cannot be delegated
to a model: labels generated by the same class of system being tested are
exactly the "fitted to noise" failure ADR-010 decision #3 exists to prevent.
Do not regenerate the sample either — it is frozen for reproducibility.

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
