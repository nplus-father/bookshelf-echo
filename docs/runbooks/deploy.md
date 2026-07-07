# Runbook: host deployment

The whole stack runs under Docker Compose at `~/workspace/ai-radar`: infra
(RabbitMQ + Postgres + Flyway) plus the four pipeline apps
(producers/enricher/digester/publisher) as Jib-built images
`ghcr.io/nplus-father/ai-radar-*`. Host ports bind `127.0.0.1` only (ADR-006);
each app's metrics port is exposed only on the internal Docker network and
scraped by the shared Prometheus — never published to the host.

## First-time setup

```bash
cd ~/workspace/ai-radar
cp .env.example .env      # fill RABBITMQ_PASSWORD, POSTGRES_PASSWORD, GEMINI_API_KEY
docker network create infra-shared-network 2>/dev/null || true   # normally created by the nplus-infra stack
docker login ghcr.io      # if the images are private
docker compose up -d      # infra + flyway migrate + the 4 apps
```

The apps join two networks: the ai-radar default (to reach `postgres`/`rabbitmq`
by name) and the external `infra-shared-network` (so infra-prometheus can scrape
them). In-container overrides `DATABASE_URL`, `RABBITMQ_HOST`, `METRICS_BIND`
are set in compose; everything else comes from `.env`.

## Upgrade / deploy

CI (`.github/workflows/deploy.yml`) builds & pushes all four images with Jib on
every push to `main`, then deploys over SSH. Manually:

```bash
cd ~/workspace/ai-radar
git pull
docker compose pull producers enricher digester publisher
docker compose up -d
```

Schema changes are applied by the flyway compose service on `up`.

## Build images locally (no registry)

```bash
./gradlew jibDockerBuild   # builds all four images straight into the local Docker daemon
docker compose up -d
```

## Health checks

```bash
docker compose ps
docker logs -f ai-radar-digester
docker exec ai-radar-rabbitmq rabbitmqctl list_queues name messages consumers
docker compose exec postgres psql -U airadar -d airadar -c "SELECT state, count(*) FROM items GROUP BY state"
```

Monitoring: Prometheus scrapes `ai-radar-{producers,enricher,digester,publisher}:910x`
and `ai-radar-rabbitmq:15692` over `infra-shared-network`; the **AI Radar
Pipeline** Grafana dashboard shows queue depth, digest rate, LLM cost and DLQ.
All containers are visible in Portainer.

The DLQ replay CLI runs from the host against the published 127.0.0.1 ports:

```bash
./gradlew :ops:installDist
set -a; source .env; set +a
./ops/build/install/ops/bin/ops dlq list
```

## Broker upgrade discipline (ADR-002)

RabbitMQ minor upgrades changed queue semantics before (4.3 vs transient
non-exclusive queues). Before bumping the image tag: read the release notes for
queue-declaration changes, upgrade in a throwaway compose project first, and
only then the live one.
