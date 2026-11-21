-- The projection by business payment, not by notification.
--
-- Two notifications about one payment are two facts about one thing. The operations view asks "where is
-- this payment", not "which webhook arrived last", so the state is keyed by the payment the provider and
-- the policy agree on.
--
-- `latest_*` is the newest event observed. It is not proof that Salesforce applied anything: that column
-- (`remote_applied_status`) stays empty until the relay writes it, and nothing here infers it from a
-- local insert.
CREATE TABLE payment_state (
    payment_key              text        PRIMARY KEY,
    provider                 text        NOT NULL,
    country                  text        NOT NULL,
    latest_status            text        NOT NULL,
    latest_event_id          text        NOT NULL,
    latest_status_changed_at timestamptz NOT NULL,
    latest_received_at       timestamptz NOT NULL,
    observed_events          integer     NOT NULL DEFAULT 1,
    remote_applied_status    text,
    updated_at               timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN payment_state.latest_status IS
    'Newest status by the provider status timestamp, not by arrival: a delayed capture never replaces a newer reversal.';
COMMENT ON COLUMN payment_state.remote_applied_status IS
    'Written only by the Salesforce relay. A local write is not evidence that the remote system applied anything.';
COMMENT ON COLUMN payment_state.observed_events IS
    'How many notifications this payment produced, including the ones that did not move the status.';
