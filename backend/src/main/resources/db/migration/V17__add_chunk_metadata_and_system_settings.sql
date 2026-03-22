-- 1. document_chunk에 metadata JSONB 컬럼 추가
ALTER TABLE document_chunk ADD COLUMN metadata JSONB DEFAULT '{}';

-- 2. 시스템 설정 테이블 (key-value with JSONB)
CREATE TABLE IF NOT EXISTS system_settings (
    key   VARCHAR(100) PRIMARY KEY,
    value JSONB NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

-- 기본 청킹 설정 삽입
INSERT INTO system_settings (key, value) VALUES
    ('chunking', '{
        "mode": "semantic",
        "fixedChunkSize": 1000,
        "fixedOverlap": 200,
        "semanticBufferSize": 1,
        "semanticBreakpointPercentile": 90,
        "semanticMinChunkSize": 200,
        "semanticMaxChunkSize": 1500,
        "childChunkSize": 500,
        "childOverlap": 100
    }'::jsonb),
    ('embedding', '{
        "batchSize": 32,
        "concurrency": 2
    }'::jsonb)
ON CONFLICT (key) DO NOTHING;
