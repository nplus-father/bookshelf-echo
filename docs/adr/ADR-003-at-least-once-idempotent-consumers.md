# ADR-003: At-least-once delivery with idempotent consumers

- Status: Accepted
- Date: 2026-07-06

## Context

Every stage consumes from a durable queue. Messages can be redelivered: consumer
crash between side effect and ack, broker restart, network partition during ack.
We must choose the delivery contract consumers are written against.

## Considered options

- **Exactly-once** — not achievable end-to-end across broker + DB + external
  APIs without distributed transactions or an outbox/inbox scheme; the cost is
  not justified at this scale, and pretending to have it is a classic design
  smell.
- **At-most-once** (auto-ack) — silently drops items on crash; unacceptable for
  a pipeline whose output is "the record of what happened today".
- **At-least-once + idempotent consumers (chosen)** — redelivery is expected
  and harmless because every side effect is keyed.

## Decision

1. **Manual ack, after the database transaction commits.** The ack is the last
   step; a crash before it yields redelivery, never loss.
2. **Idempotency keys per stage:**
   - Enricher: `items UNIQUE (source, external_id)` with
     `INSERT … ON CONFLICT DO NOTHING`. A conflict means "already **inserted**",
     which is not the same as "already processed": the insert commits before the
     fetch and the `ENRICHED` transition, so a crash in between leaves a row in
     `RECEIVED` that a later redelivery must **resume**, not ack away. The
     conflicting row's state decides — past `RECEIVED` → no-op; still
     `RECEIVED` → redo the enrichment (every step of it is itself idempotent).
   - Cross-source dedup: `content_hash` (canonical URL + normalized title,
     `UrlCanonicalizer` in `:common`); duplicates are recorded with
     `state = DUPLICATE` and `duplicate_of`, not digested twice.
   - Digester/Publisher: state-machine transitions
     (`RECEIVED → ENRICHED → DIGESTED → PUBLISHED`) are conditional updates
     (`WHERE state = :expected`); an already-advanced row makes redelivery a
     no-op.
3. **The wire contract (`ItemEnvelope`) evolves additively only**; breaking
   changes require a new schema version and routing key.

## Consequences

- Consumers must never perform an unkeyed side effect (e.g. "append to file")
  before ack — reviews check for this.
- "Row exists" is never sufficient grounds to ack: a consumer that commits a row
  and then does more work must be able to resume from that row's state, or the
  contract degrades from at-least-once to at-most-once for exactly the crash
  window it was meant to cover.
- Duplicate LLM calls are prevented by the state machine, protecting the cost
  budget as well as correctness.
- Redelivery storms show up as `duplicate ack` metrics rather than corrupt
  output — observable, not silent.
