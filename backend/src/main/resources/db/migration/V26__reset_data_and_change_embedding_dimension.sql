-- 사용자 데이터(app_user, refresh_token)를 제외한 모든 데이터 삭제
-- FK 의존 순서를 고려하여 삭제

-- questionnaire 관련
TRUNCATE TABLE questionnaire_job_persona, questionnaire_job_document, questionnaire_job CASCADE;

-- generation 관련
TRUNCATE TABLE generation_job CASCADE;

-- eval 관련
TRUNCATE TABLE eval_question, eval_run CASCADE;
TRUNCATE TABLE evaluation_result CASCADE;

-- document 관련 (chunk, tag, collection 매핑 포함 - CASCADE)
TRUNCATE TABLE document CASCADE;
TRUNCATE TABLE document_tag CASCADE;
TRUNCATE TABLE document_collection CASCADE;

-- conversation 관련
TRUNCATE TABLE conversation_message, conversation CASCADE;

-- 운영 데이터
TRUNCATE TABLE pipeline_trace CASCADE;
TRUNCATE TABLE token_usage CASCADE;
TRUNCATE TABLE audit_log CASCADE;
TRUNCATE TABLE system_settings CASCADE;

-- 기존 embedding 모델 삭제 (qwen3-embedding)
DELETE FROM llm_model WHERE purpose = 'EMBEDDING';

-- embedding 차원 변경: 2560 → 1536 (text-embedding-3-small)
ALTER TABLE document_chunk ALTER COLUMN embedding TYPE vector(1536);
