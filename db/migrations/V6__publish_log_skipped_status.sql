-- PUBLISH_DIGEST gate (372433e) records SKIPPED when digest publishing is
-- disabled, but the status check still only allowed SUCCESS/FAILED — every
-- daily publish attempt dead-lettered on the insert.
ALTER TABLE publish_log DROP CONSTRAINT publish_log_status_check;
ALTER TABLE publish_log ADD CONSTRAINT publish_log_status_check
    CHECK (status IN ('SUCCESS', 'FAILED', 'SKIPPED'));
