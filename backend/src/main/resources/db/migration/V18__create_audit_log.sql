CREATE TABLE audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID,
    user_email  VARCHAR(255),
    action      VARCHAR(50) NOT NULL,
    resource    VARCHAR(50),
    resource_id VARCHAR(100),
    detail      JSONB,
    ip_address  VARCHAR(50),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_created ON audit_log (created_at DESC);
CREATE INDEX idx_audit_log_user ON audit_log (user_id);
CREATE INDEX idx_audit_log_action ON audit_log (action);
