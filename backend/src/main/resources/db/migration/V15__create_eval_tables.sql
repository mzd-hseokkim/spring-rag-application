CREATE TABLE eval_run (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(200) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_questions     INT NOT NULL DEFAULT 0,
    completed_questions INT NOT NULL DEFAULT 0,
    avg_faithfulness    DOUBLE PRECISION,
    avg_relevance       DOUBLE PRECISION,
    avg_correctness     DOUBLE PRECISION,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    completed_at        TIMESTAMP
);

CREATE TABLE eval_question (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    eval_run_id       UUID NOT NULL REFERENCES eval_run(id) ON DELETE CASCADE,
    document_id       UUID REFERENCES document(id),
    question          TEXT NOT NULL,
    expected_answer   TEXT NOT NULL,
    question_type     VARCHAR(20) NOT NULL,
    actual_response   TEXT,
    retrieved_context TEXT,
    faithfulness      DOUBLE PRECISION,
    relevance         DOUBLE PRECISION,
    correctness       DOUBLE PRECISION,
    judge_comment     TEXT,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_eval_question_run ON eval_question (eval_run_id);
