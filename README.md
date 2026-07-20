# bookshelf-echo

A queue-based pipeline that collects AI-related news and papers from multiple
sources, digests them with an LLM, and publishes a daily static digest — plus a
public dashboard of the **system's own health metrics** (queue depth, latency,
DLQ, cost).

The interesting part is not the content; it is the reliability semantics:
at-least-once delivery with idempotent consumers, a TTL+DLX retry ladder, a
dead-letter queue with replay tooling, and an LLM cost circuit breaker whose
backlog lives in the queue. Every non-obvious decision has an ADR in
[`docs/adr/`](docs/adr/).

## Architecture

```
producers (hn, arxiv, gh-trending, blogs, reddit — each on its own cadence)
    │  publish ItemEnvelope, rk = item.<source>
    ▼
RabbitMQ 4.x ── ingest.q ─▶ enricher ── digest.q ─▶ digester ── publish.q ─▶ publisher
    │                        dedup +                 Gemini API               markdown +
    │                        full text               (per item,               metrics JSON
    │                        → Postgres              budget-gated)            → CONTENT_DIR
    │                                                                              │
    ├── retry.{30s,5m,1h}.q   (TTL + DLX ladder, no consumers — ADR-004)           ▼
    └── dlq.q                 (+ replay CLI)                              site-publisher
                                                                          (sidecar, git)
                                                                                   │
                                                     site repo ─▶ GitHub Actions ─▶ Pages
```

State machine per item: `RECEIVED → ENRICHED → MATCHED → DIGESTED → PUBLISHED`
(terminal: `DUPLICATE`, `FAILED`, `NO_RESONANCE`). All transitions are
idempotent (ADR-003).

Between enricher and digester sits the **resonance gate** (`matcher`,
ADR-010): the news is embedded against the owner's book library
(library-bridge `/search`, sqlite-vec + voyage-3-large) *before any LLM
spend*. Items whose nearest book is too far park in `NO_RESONANCE` at zero
LLM cost; the rest carry their matched books/passages forward. The daily
essay (`essays/`) pairs the strongest-resonance pick with at most two books —
the essayist may decline when the material is only a keyword coincidence
(寧缺勿濫: days without an essay are expected). Every draft's quotes are checked
verbatim against the source chapters before it can publish; that check is a
string comparison, not another model call (ADR-011).

A second judgment tier sits on top (M5, ADR-009): once per UTC day the
digester's **curator** re-ranks the day's digests relative to each other with a
stronger model (`SELECT_MODEL`) and keeps at most `SHORTLIST_MAX_PER_DAY` in
the `shortlist` pool — the input for the daily book-informed commentary (M6).
The pool is a Postgres table orthogonal to the state machine above; selection
is a DB batch job, not a queue stage, because its unit of work is the day's
whole candidate set.

Zero-inbound posture: the host only makes outbound calls (source APIs, LLM,
git push). Every compose port binds to `127.0.0.1`. The public face is a
statically built site.

## Modules

| Module       | Role                                                              |
|--------------|-------------------------------------------------------------------|
| `common`     | Wire contract (`ItemEnvelope`), broker topology, URL canonicalizer |
| `producers`  | Source pollers on independent cadences (coroutine scheduler)       |
| `enricher`   | Dedup (two layers) + full-text fetch                               |
| `matcher`    | Resonance gate vs the book library, pre-LLM (ADR-010)              |
| `digester`   | LLM digestion (daily cap), cost circuit breaker, daily curator → shortlist (ADR-009) |
| `publisher`  | Renders digests + metrics snapshots into `CONTENT_DIR`             |
| `ops`        | CLI: `dlq list / replay / purge`, `republish <day>`, `redrive`, `shortlist` |

Delivery to the site repo is the `site-publisher` compose sidecar (`alpine/git`
running [`config/site-publish.sh`](config/site-publish.sh)), not the publisher:
it rebases onto `origin/main`, copies the markdown into `content/` and the
metrics snapshot into `public/`, and pushes. The snapshot lands at
`/data/metrics/latest.json` on the site, where the dashboard page renders it —
that file is the dashboard's only data source (ADR-005: no runtime backend).

## Running

```bash
cp .env.example .env         # fill in passwords
docker compose up -d         # rabbitmq + postgres, migrations via flyway
./gradlew build              # compile + tests
```

## Status

| Milestone | Scope                                                        | State |
|-----------|--------------------------------------------------------------|-------|
| M0        | Skeleton, compose stack, schema, ADRs 001–003                | done  |
| M1        | First producer (`hn`) through the full pipeline, daily digest | done  |
| M2        | DLQ replay CLI (`ops`), Prometheus metrics, snapshot exporter, systemd units, runbooks, dashboard page | done  |
| M3        | arXiv/GitHub-trending/blogs producers (reddit stub: needs OAuth), per-feed caps, weekly rollup v1 | done (Gemini Batch API deferred) |
| M4        | SLO doc, runbooks, 30-day live data, tech write-up            | —     |
| M5        | Selection tier: daily curator, shortlist pool, per-tier model config (ADR-009) | done  |
| M6        | news-echo Phase 1+2: resonance gate (`matcher`, ADR-010), news RSS source, daily book-informed essay (`essayist`) | done  |
| M7        | Site templates for essays, LINE push reuse (nplus-backend job), live threshold calibration | —     |
