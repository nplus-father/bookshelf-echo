-- Freshness cutoff (news-echo): the daily digest cap (ADR-007) is FIFO, so a
-- backlog of days-old items starves the fresh ones — the pipeline would keep
-- writing commentary off week-old news. The digester now drops items older
-- than MATCH_MAX_AGE_DAYS to STALE, terminal, at zero LLM cost, before the cap.
-- STALE is its own state (not FAILED, not NO_RESONANCE): the item was fine and
-- did resonate, it just aged out of relevance.
ALTER TABLE items DROP CONSTRAINT items_state_check;
ALTER TABLE items ADD CONSTRAINT items_state_check
    CHECK (state IN ('RECEIVED', 'ENRICHED', 'MATCHED', 'DIGESTED', 'PUBLISHED',
                     'DUPLICATE', 'FAILED', 'NO_RESONANCE', 'STALE'));
