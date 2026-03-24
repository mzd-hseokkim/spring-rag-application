-- 페르소나 마스터 테이블
CREATE TABLE persona (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(100) NOT NULL,
    role         VARCHAR(200) NOT NULL,
    focus_areas  VARCHAR(500),
    prompt       TEXT,
    is_default   BOOLEAN NOT NULL DEFAULT false,
    user_id      UUID REFERENCES app_user(id),
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_persona_user ON persona (user_id);

-- 질의서 생성 Job
CREATE TABLE questionnaire_job (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status           VARCHAR(20) NOT NULL,
    user_input       TEXT,
    generated_qna    JSONB,
    output_file_path VARCHAR(500),
    current_persona  INT NOT NULL DEFAULT 0,
    total_personas   INT NOT NULL DEFAULT 0,
    error_message    VARCHAR(1000),
    user_id          UUID NOT NULL REFERENCES app_user(id),
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_questionnaire_job_user ON questionnaire_job (user_id);
CREATE INDEX idx_questionnaire_job_status ON questionnaire_job (status);

-- Job ↔ Document 연결
CREATE TABLE questionnaire_job_document (
    job_id      UUID NOT NULL REFERENCES questionnaire_job(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id),
    PRIMARY KEY (job_id, document_id)
);

-- Job ↔ Persona 연결
CREATE TABLE questionnaire_job_persona (
    job_id     UUID NOT NULL REFERENCES questionnaire_job(id) ON DELETE CASCADE,
    persona_id UUID NOT NULL REFERENCES persona(id),
    PRIMARY KEY (job_id, persona_id)
);

-- 기본 페르소나 시드 데이터
INSERT INTO persona (name, role, focus_areas, is_default) VALUES
('고객(발주자)', '사업을 발주한 고객 관점', '사업 목표 달성, 비용 대비 효과, 일정 준수, 의사소통 체계', true),
('기술평가위원', '기술적 타당성을 평가하는 위원', '기술 아키텍처, 구현 가능성, 기술 트렌드 적합성, 보안', true),
('가격평가위원', '비용 적정성을 평가하는 위원', '비용 산정 근거, 인력 투입 적정성, 원가 구조', true),
('경영평가위원', '수행 역량을 평가하는 위원', '유사 사업 경험, 수행 조직 구성, PM 역량, 품질 관리', true);
