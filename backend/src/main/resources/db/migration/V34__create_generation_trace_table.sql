CREATE TABLE generation_trace (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL,
    job_type VARCHAR(20) NOT NULL,
    step_name VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    started_at TIMESTAMP NOT NULL DEFAULT now(),
    completed_at TIMESTAMP,
    duration_ms BIGINT,
    error_message VARCHAR(1000),
    CONSTRAINT chk_job_type CHECK (job_type IN ('GENERATION', 'QUESTIONNAIRE'))
);

CREATE INDEX idx_generation_trace_job_id ON generation_trace(job_id);
CREATE INDEX idx_generation_trace_started_at ON generation_trace(started_at DESC);
