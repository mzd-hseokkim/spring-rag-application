CREATE TABLE pipeline_trace (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id        VARCHAR(20) NOT NULL,
    session_id      VARCHAR(100),
    user_id         UUID REFERENCES app_user(id),
    query           TEXT NOT NULL,
    agent_action    VARCHAR(30),
    total_latency   INT NOT NULL,
    steps           JSONB NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_pipeline_trace_created ON pipeline_trace (created_at DESC);
CREATE INDEX idx_pipeline_trace_user ON pipeline_trace (user_id);

CREATE TABLE token_usage (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES app_user(id),
    model_name      VARCHAR(200) NOT NULL,
    purpose         VARCHAR(30) NOT NULL,
    input_tokens    INT NOT NULL DEFAULT 0,
    output_tokens   INT NOT NULL DEFAULT 0,
    session_id      VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_token_usage_user ON token_usage (user_id, created_at DESC);
CREATE INDEX idx_token_usage_model ON token_usage (model_name, created_at DESC);
CREATE INDEX idx_token_usage_created ON token_usage (created_at DESC);

CREATE TABLE evaluation_result (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id        VARCHAR(20),
    user_id         UUID REFERENCES app_user(id),
    query           TEXT NOT NULL,
    faithfulness    DOUBLE PRECISION,
    relevance       DOUBLE PRECISION,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_evaluation_created ON evaluation_result (created_at DESC);
