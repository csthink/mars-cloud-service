-- Retention cleanup deletes rows by revocation time; the existing (user_id, last_seen_at) index does not cover it.
CREATE INDEX idx_session_revoked ON sys_session (revoked_at);
