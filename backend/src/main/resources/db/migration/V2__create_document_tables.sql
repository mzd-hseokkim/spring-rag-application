CREATE TABLE document (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename      VARCHAR(500)  NOT NULL,
    content_type  VARCHAR(100)  NOT NULL,
    file_size     BIGINT        NOT NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    chunk_count   INT           NOT NULL DEFAULT 0,
    created_at    TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE TABLE document_chunk (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id   UUID          NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    content       TEXT          NOT NULL,
    chunk_index   INT           NOT NULL,
    embedding     vector(1536),
    content_tsv   tsvector,
    created_at    TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX idx_chunk_document_id ON document_chunk(document_id);
CREATE INDEX idx_chunk_embedding ON document_chunk USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX idx_chunk_content_tsv ON document_chunk USING gin (content_tsv);
