-- An audit record captures the requested identity, including rejected/unknown targets.
-- It is not a foreign key: the record must survive even if the referenced event is later purged.
CREATE TABLE replay_command (
    idempotency_key text        PRIMARY KEY CHECK (length(trim(idempotency_key)) > 0),
    requested_at    timestamptz NOT NULL DEFAULT now(),
    requester       text,
    reason          text,
    provider        text,
    event_id        text,
    destination     text,
    outcome         text        NOT NULL
);
