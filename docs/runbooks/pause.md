# Runbook: stopping the pipeline on purpose

`PIPELINE_PAUSED=true` in the host `.env`, then `docker compose up -d`.

```bash
cd ~/workspace/bookshelf-echo
echo 'PIPELINE_PAUSED=true' >> .env
docker compose up -d producers enricher matcher digester publisher
docker logs bookshelf-echo-digester | grep PIPELINE_PAUSED   # confirms it took
```

Resume: delete the line, `docker compose up -d` again. The flag is read once at
startup, so nothing changes until the restart — deliberately, since a flag that
can flip mid-flight is a flag a half-processed item can straddle.

## What stops, and what deliberately does not

| | paused |
|---|---|
| `producers` | polls nothing; process parks, `/metrics` stays bound |
| `enricher` / `matcher` / `digester` / `publisher` | consumers stay **registered**, each message re-parks in the 1h retry tier untouched |
| `curator` / `essayist` (the pro-tier spend) | thread never starts |
| publisher's hourly snapshot | **keeps running** |
| `site-publisher` sidecar | **keeps running** (a round with nothing to push is a healthy round) |
| nplus-backend's 08:00 LINE push | **goes quiet on its own** — see below |
| LLM spend | zero |

Consumers stay registered on purpose. Cancelling them drops the queue to
`consumers=0`, which the snapshot and the public dashboard both read as
*stalled* — the pause would then look exactly like the outage it is not.
Messages take the same come-back-later path as an exhausted budget: into
`retry.1h.<queue>.q`, headers intact, TTL expiry dead-letters them home. Nothing
is acked away, nothing reaches the DLQ, and no retry attempt is burned. Expect
`airadar_messages_total{outcome="paused"}` to tick up once an hour per parked
message; that is the loop working, not a fault.

## The reader downstream: the 08:00 LINE push

nplus-backend reads `essay.json` / `daily.json` every morning and pushes the
newest to Andrew. Those endpoints always serve *the latest item on the site* and
have no idea what the reader has already seen, so until 2026-08-17 a pause meant
the same essay went out again the next morning — and the morning after — until
it aged out of a two-day freshness window. Recorded: paused 08-16, the 08-15
essay pushed on both 08-16 and 08-17.

That was never really a pause bug. 寧缺勿濫 is a legal output, so **any** blank
day already re-sent the previous day's essay once; the pause only made it
consecutive enough to notice. The backend now keeps a watermark
(`linebot.push_watermarks`) and sends only what is strictly newer, which
collapses a pause, a blank day and a dead pipeline into one correct behaviour:
nothing new, nothing sent.

It deliberately does **not** read `paused`, even though the snapshot publishes
it. Telling a declared stop from a fault is the alerts' job — `BookshelfEchoPaused`
and `BookshelfEchoEssayStale`, both on the ENJIA_OPS channel. The LINE content
channel carries content only. What the backend does report is its own half:
`ai_radar_push_total{result}`, where `already_pushed` is the healthy silence a
pause produces (do not alert on it) and `AiRadarPushBroken` covers the three
results that mean the backend could not deliver something that existed.

## What the monitoring does

One alert changes behaviour: **`BookshelfEchoEssayStale`** carries
`unless on() (max(airadar_pipeline_paused) == 1)` and stands down. Everything
else stays armed, which is the point — pausing the product must not disarm the
monitoring of the monitoring. `SnapshotStale`, `SitePublishStale`,
`SiteDeployStale` and `MetricsStale` still guard the observability path, and it
is still running, so they stay green on their own merits rather than by
suppression.

**`BookshelfEchoPaused`** fires after 24h and then nags once a day. A pause
nobody remembers is a silence that never expires; short stops (deploy, debug,
config) never reach the threshold.

Two things a pause does *not* quiet:

- **`BookshelfEchoDlq`** — a non-empty DLQ is a backlog whether or not the
  pipeline is running. Drain it (`ops dlq list`) or accept the daily nag.
- **`ops republish` / `republish-essay`** — the publisher parks those messages
  too. Unpause first.

## Why not `docker compose stop`

It fires about a dozen alerts, nine of them critical, repeating every 4h:

| | |
|---|---|
| `ScrapeTargetDown` ×8 | five apps + `rabbitmq` + its per-queue scrape + the pg exporter |
| `SitePublishStale` | rule carries `or absent()` |
| `SnapshotStale` | same |
| `SiteDeployStale`, `MetricsStale` | `site_*` are node-exporter **textfile** metrics — the files stay on disk after the sidecar dies and merely go stale, so the monitoring cannot tell "andrew stopped it" from "it died" |

## Why not an Alertmanager silence

It works — `nplus-infra`'s deploy workflow opens a 5-minute one around compose
changes, and that is the right tool for a window you can size in advance. As a
stop switch it has three faults: it expires (and the backlog of suppressed
alerts arrives at once), it has to be remembered and renewed, and while it lasts
it hides real faults in the same blast radius. If you do need it — a genuine
full stop, host maintenance — two silences are required, because one matcher
does not cover both families:

```bash
# ScrapeTargetDown ×8: the label comes from the scrape job's static_configs
matchers: app="bookshelf-echo"
# the eight own rules: their series are node-exporter textfile metrics with no app label
matchers: alertname=~"BookshelfEcho.*"
```

Alertmanager is `http://10.43.77.96:9093` (ClusterIP pinned in
`nplus-gitops/apps/kube-prometheus-stack.yaml` for exactly this); the POST body
shape is in `nplus-infra/.github/workflows/deploy.yml`.

## Cheaper knobs, when a full stop is not what you want

- `DAILY_LLM_BUDGET_USD=0` — stops spend, but the essayist books
  `budget_skipped` and `EssayStale` still trips on day three. Pausing is
  strictly better if the goal is "stop spending".
- `SOURCES` — stops intake only. ⚠️ Setting it to a name no source has empties
  the source list, `runBlocking` falls straight through, the process exits 0 and
  `restart: unless-stopped` turns it into a restart loop (→ `ContainerRestart`
  and a flapping `ScrapeTargetDown`). To throttle intake without that, raise
  `<SOURCE>_INTERVAL_MINUTES` instead.
- `LLM_PROVIDER=fake` — free, but it still writes essays and still publishes
  them. That is a test mode, not a stop.
