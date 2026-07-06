# Runbook: host deployment

Host layout: repo at `~/workspace/ai-radar`, infra via docker-compose
(localhost-only ports), apps as **user-level systemd services** running the
`installDist` output directly on the host JVM (21+).

## First-time setup

```bash
cd ~/workspace/ai-radar
cp .env.example .env    # fill RABBITMQ_PASSWORD, POSTGRES_PASSWORD, GEMINI_API_KEY
docker compose up -d    # rabbitmq + postgres, flyway migrates automatically
./gradlew installDist

mkdir -p ~/.config/systemd/user
cp deploy/systemd/*.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now ai-radar-producers ai-radar-enricher ai-radar-digester ai-radar-publisher
loginctl enable-linger "$USER"   # keep user services alive without a login session
```

## Upgrade

```bash
cd ~/workspace/ai-radar
git pull
./gradlew installDist
systemctl --user restart 'ai-radar-*'
```

Schema changes are applied by the flyway compose service:
`docker compose run --rm flyway migrate` (or just `docker compose up -d`).

## Health checks

```bash
systemctl --user status 'ai-radar-*'
curl -s 127.0.0.1:9102/metrics | grep airadar_messages_total   # per-app: 9101-9104
docker compose exec postgres psql -U airadar -d airadar -c "SELECT state, count(*) FROM items GROUP BY state"
./ops/build/install/ops/bin/ops dlq list
```

## Broker upgrade discipline (ADR-002)

RabbitMQ minor upgrades changed queue semantics before (4.3 vs transient
non-exclusive queues). Before bumping the image tag: read the release notes
for queue-declaration changes, upgrade in a throwaway compose project first,
and only then the live one.
