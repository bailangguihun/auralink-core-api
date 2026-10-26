-- Preserve every synchronous Guide provider-dispatch attempt.  A proven
-- before-response failure may be explicitly submitted again, matching the
-- original R24 cache behaviour.  A post-response uncertainty remains a
-- durable blocker: a restart must not send the provider a second request.

ALTER TABLE catalog_painting_guide_generation_attempts
    DROP CONSTRAINT uq_catalog_guide_attempt_painting_source;

CREATE UNIQUE INDEX uq_catalog_guide_attempt_active_painting_source
    ON catalog_painting_guide_generation_attempts(painting_id, source_hash)
    WHERE status IN ('DISPATCHING', 'POST_RESPONSE_UNCERTAIN');

