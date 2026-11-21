-- The inbox: one immutable row per accepted provider notification.
--
-- The uniqueness of (provider, event_id) is the deduplication decision. Application code does not
-- look first and insert later: a concurrent second delivery reaches the constraint and loses there.
CREATE TABLE payment_event (
    intake_id          uuid        PRIMARY KEY,
    provider           text        NOT NULL,
    event_id           text        NOT NULL,
    payment_key        text        NOT NULL,
    policy_number      text        NOT NULL,
    status             text        NOT NULL,
    status_changed_at  timestamptz NOT NULL,
    occurred_at        timestamptz NOT NULL,
    received_at        timestamptz NOT NULL,
    correlation_id     text        NOT NULL,
    payload_hash       text        NOT NULL,
    canonical          jsonb       NOT NULL,
    CONSTRAINT payment_event_identity UNIQUE (provider, event_id),
    CONSTRAINT payment_event_event_id_present CHECK (length(event_id) > 0),
    CONSTRAINT payment_event_payload_hash_present CHECK (length(payload_hash) > 0)
);

COMMENT ON CONSTRAINT payment_event_identity ON payment_event IS
    'Unique by business notification identity: the second concurrent insert conflicts here.';

COMMENT ON COLUMN payment_event.canonical IS
    'The exact document the publisher will send; read back instead of rebuilt, so publication cannot drift from acceptance.';

-- Two access paths the operations work needs: by arrival (retention, backlog) and by payment.
CREATE INDEX payment_event_received_at ON payment_event (received_at);
CREATE INDEX payment_event_payment_key ON payment_event (payment_key);

-- The outbox: exactly one job per (event, destination), written in the same transaction as the event.
CREATE TABLE outbox_job (
    outbox_id        bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    intake_id        uuid        NOT NULL REFERENCES payment_event (intake_id) ON DELETE RESTRICT,
    destination      text        NOT NULL,
    state            text        NOT NULL DEFAULT 'PENDING',
    attempts         integer     NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    last_error       text,
    published_at     timestamptz,
    CONSTRAINT outbox_job_destination_known CHECK (destination IN ('CANONICAL_EVENT', 'SALESFORCE')),
    CONSTRAINT outbox_job_state_known CHECK (state IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT outbox_job_one_per_destination UNIQUE (intake_id, destination)
);

-- The due-work query reads this partial index; it never walks the whole table as the history grows.
CREATE INDEX outbox_job_due ON outbox_job (destination, next_attempt_at) WHERE state = 'PENDING';
