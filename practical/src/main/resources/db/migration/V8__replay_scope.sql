-- Preserve existing CLI keys, and prevent a key used in another country from influencing HTTP replay.
ALTER TABLE replay_command ADD COLUMN key_scope text NOT NULL DEFAULT 'CLI'
    CHECK (key_scope IN ('CLI', 'CO', 'MX', 'CL'));
ALTER TABLE replay_command DROP CONSTRAINT replay_command_pkey;
ALTER TABLE replay_command ADD PRIMARY KEY (key_scope, idempotency_key);
