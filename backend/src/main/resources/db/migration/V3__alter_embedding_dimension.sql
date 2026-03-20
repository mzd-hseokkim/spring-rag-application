DROP INDEX idx_chunk_embedding;
ALTER TABLE document_chunk ALTER COLUMN embedding TYPE vector(2560);
-- 2560 차원은 ivfflat(2000), hnsw(2000) 제한 초과
-- MVP 단계에서는 인덱스 없이 exact search 사용 (데이터 소량)
-- Phase 2에서 halfvec 또는 차원 축소 기반 인덱스 도입 예정
