-- Persist observed retries of webhook deliveries without creating a new event or a job.
-- Existing rows start counting at deployment; past duplicate deliveries were not stored.
ALTER TABLE payment_event ADD COLUMN duplicate_count bigint NOT NULL DEFAULT 0;
ALTER TABLE payment_event ADD COLUMN last_duplicate_at timestamptz;

-- Scope and page in receipt order with a deterministic tie-breaker.
CREATE INDEX payment_event_ops_country_received
    ON payment_event ((canonical ->> 'country'), received_at DESC, intake_id DESC);

-- Store the time of a job's last observable transition. Do not backfill a fictional time
-- for old retries or failures. Older successful jobs already have published_at.
ALTER TABLE outbox_job ADD COLUMN transitioned_at timestamptz;

CREATE FUNCTION stamp_outbox_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.state, NEW.outcome_code, NEW.attempts, NEW.next_attempt_at, NEW.published_at)
        IS DISTINCT FROM
       (OLD.state, OLD.outcome_code, OLD.attempts, OLD.next_attempt_at, OLD.published_at) THEN
        NEW.transitioned_at = statement_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER outbox_job_transition
    BEFORE UPDATE OF state, outcome_code, attempts, next_attempt_at, published_at ON outbox_job
    FOR EACH ROW EXECUTE FUNCTION stamp_outbox_transition();
