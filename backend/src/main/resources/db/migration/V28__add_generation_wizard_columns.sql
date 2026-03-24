-- 위자드 단계 관리
ALTER TABLE generation_job ADD COLUMN current_step INT NOT NULL DEFAULT 1;
ALTER TABLE generation_job ADD COLUMN step_status VARCHAR(20) NOT NULL DEFAULT 'IDLE';

-- 요구사항 매핑 (Step 3)
ALTER TABLE generation_job ADD COLUMN requirement_mapping JSONB;

-- 고객문서/참조문서 구분을 위한 문서 연결 테이블
CREATE TABLE generation_job_document (
    job_id        UUID NOT NULL REFERENCES generation_job(id) ON DELETE CASCADE,
    document_id   UUID NOT NULL REFERENCES document(id),
    document_role VARCHAR(20) NOT NULL DEFAULT 'REFERENCE',
    PRIMARY KEY (job_id, document_id)
);
