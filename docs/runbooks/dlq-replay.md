# Runbook: DLQ triage and replay

Messages land in `dlq.q` for exactly two reasons (ADR-004): a non-retryable
failure (parse error, non-429 4xx, schema violation) or an exhausted retry
ladder (3 attempts). Each parked message carries `x-origin-queue` and
`x-error` headers.

## Triage

```bash
./ops/build/install/ops/bin/ops dlq list 20
```

Read `x-error` first. Typical shapes:

| Error shape | Meaning | Action |
|---|---|---|
| JSON/schema errors | producer contract drift or LLM returned junk | fix code first, THEN replay |
| `Gemini returned 4xx` (non-429) | bad request / auth | check GEMINI_API_KEY / prompt, then replay |
| `retries exhausted` + network errors | downstream was down long enough to eat the ladder | safe to replay as-is |
| `item N not found` | DB/queue divergence (should not happen) | investigate before touching |

## Replay

```bash
./ops/build/install/ops/bin/ops dlq replay        # everything
./ops/build/install/ops/bin/ops dlq replay 5      # first 5 only (canary)
```

Replay resets the retry count — a replay is a deliberate fresh chance, not
attempt #4. Consumers are idempotent (ADR-003), so replaying a message whose
side effects partially completed is safe.

Canary first: replay a handful, watch the consumer log; if they park again
with the same error, the bug is still there — stop and fix.

## Purge

```bash
./ops/build/install/ops/bin/ops dlq purge --confirm
```

Only for messages that are confirmed junk (e.g. a bad producer deploy flooded
the queue with malformed payloads that were re-published upstream already).
