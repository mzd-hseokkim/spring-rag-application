-- 분산 워커 기반 문서 처리를 위한 컬럼 추가
ALTER TABLE document ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE document ADD COLUMN leased_by VARCHAR(100);
ALTER TABLE document ADD COLUMN leased_until TIMESTAMP;

-- 워커 폴링 성능을 위한 인덱스
CREATE INDEX idx_document_status_pending ON document (status) WHERE status = 'PENDING';
CREATE INDEX idx_document_leased_until ON document (leased_until) WHERE status = 'PROCESSING' AND leased_until IS NOT NULL;
