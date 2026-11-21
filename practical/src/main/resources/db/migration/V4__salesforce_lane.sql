-- The Salesforce lane needs states the canonical lane never uses: an item can be applied, terminally
-- rejected, or held because nobody knows whether it landed.
--
-- HELD exists for one specific case and no other: a write whose outcome is unknown (a timeout, or a
-- response lost after the remote system applied it). Marking it failed would retry a write that may have
-- landed; marking it done would claim a success nobody observed. Holding it is the only honest state, and
-- a human or a reconciliation job resolves it.
ALTER TABLE outbox_job DROP CONSTRAINT outbox_job_state_known;
ALTER TABLE outbox_job ADD CONSTRAINT outbox_job_state_known
    CHECK (state IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'DEAD', 'HELD'));

-- The stable reason code an operator filters on. Never a raw remote message.
ALTER TABLE outbox_job ADD COLUMN outcome_code text;

COMMENT ON COLUMN outbox_job.outcome_code IS
    'Stable code from the relay: APPLIED, STALE_SKIPPED, INVALID_FIELD, RATE_LIMITED, LOST_RESPONSE, ... Never a raw remote error string.';

-- Finding the in-flight work for one payment is how the relay keeps two writers away from the same key.
CREATE INDEX outbox_job_payment_inflight ON outbox_job (state, claim_expires_at)
    WHERE state = 'CLAIMED';

-- What a worker must read before deciding whether an event is the newest for its payment.
CREATE INDEX payment_state_status_time ON payment_state (latest_status_changed_at);
