CREATE TABLE llm_model (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider      VARCHAR(20)   NOT NULL,
    model_id      VARCHAR(200)  NOT NULL,
    display_name  VARCHAR(200)  NOT NULL,
    purpose       VARCHAR(20)   NOT NULL,
    is_default    BOOLEAN       NOT NULL DEFAULT false,
    is_active     BOOLEAN       NOT NULL DEFAULT true,
    base_url      VARCHAR(500),
    api_key_ref   VARCHAR(100),
    temperature   DOUBLE PRECISION DEFAULT 0.3,
    max_tokens    INT,
    created_at    TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT now(),
    UNIQUE(provider, model_id, purpose)
);
