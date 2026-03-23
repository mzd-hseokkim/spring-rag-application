CREATE TABLE document_template (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(1000),
    output_format   VARCHAR(20) NOT NULL,
    section_schema  JSONB,
    system_prompt   TEXT,
    template_path   VARCHAR(500),
    is_public       BOOLEAN NOT NULL DEFAULT false,
    user_id         UUID REFERENCES app_user(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE generation_job (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status              VARCHAR(20) NOT NULL,
    template_id         UUID NOT NULL REFERENCES document_template(id),
    user_input          TEXT NOT NULL,
    outline             JSONB,
    generated_sections  JSONB,
    output_file_path    VARCHAR(500),
    current_section     INT NOT NULL DEFAULT 0,
    total_sections      INT NOT NULL DEFAULT 0,
    error_message       VARCHAR(1000),
    user_id             UUID NOT NULL REFERENCES app_user(id),
    conversation_id     UUID,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_generation_job_user ON generation_job (user_id);
CREATE INDEX idx_generation_job_status ON generation_job (status);
CREATE INDEX idx_document_template_user ON document_template (user_id);
