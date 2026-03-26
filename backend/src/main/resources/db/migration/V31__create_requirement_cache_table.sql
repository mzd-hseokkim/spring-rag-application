-- 요구사항 추출 결과 영구 캐시 (L2)
-- 문서 생성(generation)과 질의서(questionnaire) 양쪽에서 공유
CREATE TABLE requirement_cache (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_ids_hash    VARCHAR(16) NOT NULL UNIQUE,
    document_ids    UUID[]      NOT NULL,
    requirements    JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 문서 재인제스트 시 해당 문서를 포함하는 캐시 엔트리를 빠르게 찾기 위한 GIN 인덱스
CREATE INDEX idx_requirement_cache_doc_ids ON requirement_cache USING GIN (document_ids);
