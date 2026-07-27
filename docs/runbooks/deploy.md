# Runbook: host deployment

The whole stack runs under Docker Compose at `~/workspace/bookshelf-echo`: infra
(RabbitMQ + Postgres + Flyway) plus the five pipeline apps
(producers/enricher/matcher/digester/publisher) as Jib-built images
`ghcr.io/nplus-father/bookshelf-echo-*`. Host ports bind `127.0.0.1` only (ADR-006);
each app's metrics port is exposed only on the internal Docker network and
scraped by the shared Prometheus — never published to the host.

## First-time setup

```bash
cd ~/workspace/bookshelf-echo
cp .env.example .env      # fill RABBITMQ_PASSWORD, POSTGRES_PASSWORD, GEMINI_API_KEY, SITE_GIT_TOKEN
docker network create infra-shared-network 2>/dev/null || true   # normally created by the nplus-infra stack
docker login ghcr.io      # if the images are private
docker compose up -d      # infra + flyway migrate + the 4 apps
```

The apps join two networks: the bookshelf-echo default (to reach `postgres`/`rabbitmq`
by name) and the external `infra-shared-network` (so infra-prometheus can scrape
them). In-container overrides `DATABASE_URL`, `RABBITMQ_HOST`, `RABBITMQ_PORT`,
`RABBITMQ_MGMT_PORT`, `METRICS_BIND` are set in compose; everything else comes
from `.env`. The `*_PORT` values in `.env` are host bindings only — remapping one
to dodge a host conflict must not (and now does not) change in-container
addressing.

## Upgrade / deploy

CI (`.github/workflows/deploy.yml`) runs `./gradlew build` — compile plus the
whole test suite — before it pushes anything, then builds & pushes all four
images with Jib on every push to `main` and deploys over SSH. The test step is
not decorative: the `jib` task graph is compile → jar → push and never runs a
test, so until it was added a red suite deployed to prod unremarked. A failing
test now stops the deploy.

The SQL tests need Docker (testcontainers starts a real Postgres and applies
`db/migrations/`); where Docker is absent they skip rather than fail, so a
green local `build` on a Docker-less machine has covered less than CI does.

Manually:

```bash
cd ~/workspace/bookshelf-echo
git pull
docker compose pull producers enricher matcher digester publisher
docker compose up -d
```

Schema changes are applied by the flyway compose service on `up`.

### news-echo Phase 2 rollout (one-time, ADR-010)

1. `.env` needs `LIBRARY_SECRET` (same value as the bridge's `BRIDGE_SECRET`
   in nplus-infra `bridge.env`); `LIBRARY_URL` defaults to
   `http://library-bridge:7788` on `infra-shared-network`.
2. The library-bridge image must include the `/search`+`/chapter` routes
   (book-library-hub commit `37cc39b` or later) — rebuild/redeploy it first.
3. Items already ENRICHED have messages parked on digest.q; the digester
   now no-ops them (guard is MATCHED). Run `ops redrive --apply` once after
   the deploy to re-route them through match.q.

## Build images locally (no registry)

```bash
./gradlew jibDockerBuild   # builds all five images straight into the local Docker daemon
docker compose up -d
```

## Health checks

```bash
docker compose ps
docker logs -f bookshelf-echo-digester
docker exec bookshelf-echo-rabbitmq rabbitmqctl list_queues name messages consumers
docker compose exec postgres psql -U airadar -d airadar -c "SELECT state, count(*) FROM items GROUP BY state"
```

Monitoring: Prometheus scrapes `bookshelf-echo-{producers,enricher,matcher,digester,publisher}:910x`
and `bookshelf-echo-rabbitmq:15692` over `infra-shared-network`; the **Bookshelf Echo
Pipeline** Grafana dashboard shows queue depth, digest rate, LLM cost and DLQ.
All containers are visible in Portainer.

Logging is configured in `common/src/main/resources/logback.xml` (shared by all
five apps — logback takes the first config on the classpath). Root is INFO with
UTC, dated timestamps; HikariCP, the AMQP client and the JDBC driver are muted
to WARN. Set `LOG_LEVEL=DEBUG` in the host `.env` and restart the one container
to get the detail back without a rebuild. Before this config existed logback
defaulted to root=DEBUG and `docker logs` was pool statistics every 30 seconds,
so grepping for an application line was hopeless.

`airadar_snapshot_last_success_timestamp_seconds` is the publisher's own
heartbeat: the epoch second of its last successful snapshot, with
`airadar_snapshot_failures_total` beside it. The grafana rule
`bookshelf-echo-snapshot-stale` (nplus-infra) fires when
`time() - <gauge> > 9000` (2.5 hourly intervals) or when the series disappears
altogether. It exists because the publisher went silent for 12 hours on
2026-07-19 with the rest of the pipeline healthy, and nothing said so — the
public dashboard kept serving the last file it had received, so a stale snapshot
was indistinguishable from a fresh one. The dashboard page now also labels its
own age.

`airadar_llm_latency_seconds` times every LLM call, tagged `purpose`, `model`
and `outcome` (ok/error). At one ESSAY call a day, use a 24h window — shorter
ranges are mostly empty.

`airadar_llm_cost_usd_total` and `airadar_llm_tokens_total` carry `purpose`
(DIGEST/SELECT/JUDGE/ESSAY) and `model` labels, and cover every tier rather than
the digest path alone. The existing cost panels already aggregate with `sum()`,
so they keep working unchanged — but they now include the pro-tier SELECT and
ESSAY spend that used to be invisible. **A jump on those panels right after this
deploy is the old blind spot becoming visible, not new spend.** `sum by
(purpose)` is what answers "where did the money go".

### The dashboard's data lives on a branch, not on the site

Since 2026-07-27 the metrics snapshot goes to the site repo's `data` branch, not
into `main` (ADR-005 amendment). Three places to look when the dashboard's
numbers stop moving:

```bash
# 1. is the sidecar still pushing?  (metric + log)
docker compose logs site-publisher | grep "metrics sync FAILED"
# 2. what does the branch actually hold?
curl -s https://raw.githubusercontent.com/nplus-father/bookshelf-echo-site/data/metrics/latest.json | head -c 200
# 3. is the publisher still writing the file the sidecar reads?
ls -l out/content/data/metrics/latest.json
```

`BookshelfEchoMetricsStale` (90 min) is the alert for step 1–2;
`BookshelfEchoSnapshotStale` (2.5 h) covers step 3. The page itself always
states how old its data is, so "the dashboard looks fine" is never the check.

### Essays that never come

`BookshelfEchoEssayStale` fires after three days without an essay. A single
missing day is a legal outcome (寧缺勿濫), which is exactly why every other
signal stays green through a real outage: queues drain, the site builds, the
sidecar succeeds every round. Read the outcome counter to tell the cases apart —
`airadar_essay_runs_total{outcome=...}`: `budget_skipped`, `judge_rejected`,
`skipped` (the model declined), `unverified_quotes`, `attempts_exhausted`. If
`ESSAY_MODEL` is a `-preview` id, also check the model still exists: preview
names get retired, and the whole tier fails at once when they do.

The ops CLI runs from the host against the published 127.0.0.1 ports:

```bash
./gradlew :ops:installDist
set -a; source .env; set +a
./ops/build/install/ops/bin/ops dlq list
```

## Items stranded mid-pipeline

Every stage commits its state transition and only then publishes the message for
the next queue, so a process that dies in between leaves the row advanced with
nothing to drive it. Symptom: `items` in `ENRICHED` outnumber the messages across
`digest.q` + `retry.*.digest.q` (both are on the dashboard snapshot), and the
count never drains.

```bash
./ops/build/install/ops/bin/ops redrive           # counts only
./ops/build/install/ops/bin/ops redrive --apply   # re-queue them
```

Re-queuing the whole state is safe rather than surgical: nothing in the DB
distinguishes a stranded item from one legitimately waiting its turn under the
daily cap, and ADR-003's idempotent consumers mean a duplicate for an
already-queued item costs one trip round the retry ladder and then no-ops. No
duplicate LLM spend.

## Rebuilding a day's digest page

`ops republish <YYYY-MM-DD>` re-emits one of that UTC day's items onto
`publish.q`; the publisher then regenerates the page from Postgres. Regeneration
reads the whole day and is idempotent, so it is safe to re-run.

```bash
./ops/build/install/ops/bin/ops republish 2026-07-15
```

Use it whenever a page is missing or stale on the site but the digests exist in
the DB — the DB is the source of truth for page content.

> **One-off after the digest-day fix.** The publisher used to key each page on
> `items.received_at` while filling it from `digests.created_at`. Those clocks
> only lined up by luck, so each day's digests were published a day late, onto
> whichever page the triggering item happened to arrive on. Pages are now keyed
> on `digests.created_at`. The day still in flight when that fix deploys never
> gets its page written — rebuild that one day by hand, once:
>
> ```bash
> ./ops/build/install/ops/bin/ops republish <in-flight day>   # e.g. the deploy day
> ```

## Migrating already-published essays after an EssayRenderer change

Essay files are **not** hand-editable: `config/site-publish.sh` mirrors the
publisher's whole `essays/` output dir into the site checkout every cycle
(`cp -r /src/essays/. /repo/content/essays/`), so any manual edit to
`content/essays/<day>.md` is reverted within one interval to whatever the
publisher last wrote to its output volume. The volume — i.e. the publisher — is
the source of truth for essay content.

So a change to `EssayRenderer` (new frontmatter, etc.) only reaches essays
composed *after* the deploy. Essays already on disk keep their old format until
the publisher re-renders them. After deploying the new publisher, re-render each
affected day:

```bash
./ops/build/install/ops/bin/ops republish-essay 2026-07-17
```

> **On prod that command does not run.** `nplus.space` has no JDK/JRE at all —
> every service lives in a container — so the dist is there but `java` is not
> (`JAVA_HOME is not set`). A throwaway `eclipse-temurin` container does not fix
> it either: Postgres is now the k3s CNPG cluster and a plain
> `--network bookshelf-echo_default` container cannot reach that ClusterIP.
>
> Send the stage message directly instead — that is all `republish-essay` does:
>
> ```bash
> cd ~/workspace/bookshelf-echo
> u=$(grep -E '^RABBITMQ_USER=' .env | cut -d= -f2)
> p=$(grep -E '^RABBITMQ_PASSWORD=' .env | cut -d= -f2)
> curl -s -u "$u:$p" -X POST "http://172.30.0.1:15672/api/exchanges/%2f/amq.default/publish" \
>   -H 'content-type: application/json' \
>   -d '{"properties":{},"routing_key":"publish.q","payload":"{\"itemId\":14814,\"kind\":\"essay\"}","payload_encoding":"string"}'
> ```
>
> `{"routed":true}` means it landed. Item ids come from the `essays` table:
> `kubectl exec -n bookshelf-pg bookshelf-pg-1 -c postgres -- psql -U postgres -d airadar -c "select day, item_id from essays order by day"`.
> Idempotent, so re-running the whole set is safe (2026-07-27 did exactly that
> for all eight published essays).

This re-emits the day's essay message (`kind=essay`) onto `publish.q`; the
publisher rewrites `essays/<day>.md` from Postgres with the current renderer, and
site-publisher mirrors it on the next cycle. Idempotent, so safe to re-run. The
book/chapter fields it can emit are only as rich as that essay's stored
`essays.books` JSON (e.g. `chapter_id` is present only if retrieval captured it
at compose time).

## Broker upgrade discipline (ADR-002)

RabbitMQ minor upgrades changed queue semantics before (4.3 vs transient
non-exclusive queues). Before bumping the image tag: read the release notes for
queue-declaration changes, upgrade in a throwaway compose project first, and
only then the live one.
