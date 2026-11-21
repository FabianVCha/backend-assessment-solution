-- A claim is a lease, not a lock: a worker that dies holding it must not park the job forever.
--
-- The token is what makes completion conditional. A worker whose lease expired still has the old token,
-- and the update that would mark the job published finds a different one: it changes no rows, and the
-- job stays with whoever holds it now. Without the token, a slow worker finishing after a takeover
-- would mark someone else's job done and the payment would never be published.
ALTER TABLE outbox_job
    ADD COLUMN claim_owner      text,
    ADD COLUMN claim_token      uuid,
    ADD COLUMN claim_expires_at timestamptz;

ALTER TABLE outbox_job DROP CONSTRAINT outbox_job_state_known;
ALTER TABLE outbox_job ADD CONSTRAINT outbox_job_state_known
    CHECK (state IN ('PENDING', 'CLAIMED', 'PUBLISHED'));

-- Finding expired leases is a different query from finding due work, and both must stay cheap.
CREATE INDEX outbox_job_lease ON outbox_job (claim_expires_at) WHERE state = 'CLAIMED';

COMMENT ON COLUMN outbox_job.claim_token IS
    'Set when the row is claimed; every completion must present it, so a stale worker cannot finish another one''s job.';
COMMENT ON COLUMN outbox_job.claim_expires_at IS
    'After this instant the lease is dead and any worker may take the job, even if the first one is still running.';
