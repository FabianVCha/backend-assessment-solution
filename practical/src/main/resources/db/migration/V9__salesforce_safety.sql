-- Preserve rolling call admissions while moving concurrency to recoverable reservations.
CREATE TABLE salesforce_budget_lease (
    token uuid PRIMARY KEY,
    expires_at timestamptz NOT NULL
);
CREATE INDEX salesforce_budget_lease_expiry ON salesforce_budget_lease (expires_at);

-- Conservative precedence when a provider timestamp does not distinguish statuses.
CREATE FUNCTION payment_status_rank(status text) RETURNS integer
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN CASE status
        WHEN 'REFUNDED' THEN 4
        WHEN 'CAPTURED' THEN 3
        WHEN 'FAILED' THEN 2
        WHEN 'PENDING' THEN 1
        ELSE 0
    END;

-- Rebuild winners that used the old arrival-order rule; never invent remote confirmation.
WITH latest AS (
    SELECT DISTINCT ON (payment_key) payment_key, status, event_id, status_changed_at, received_at
    FROM payment_event
    ORDER BY payment_key, status_changed_at DESC, payment_status_rank(status) DESC,
             event_id COLLATE "C" DESC
)
UPDATE payment_state p
SET latest_status = l.status, latest_event_id = l.event_id,
    latest_status_changed_at = l.status_changed_at, latest_received_at = l.received_at
FROM latest l WHERE l.payment_key = p.payment_key;
