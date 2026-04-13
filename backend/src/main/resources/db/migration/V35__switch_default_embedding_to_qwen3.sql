-- 기본 EMBEDDING 모델을 Azure text-embedding-3-small → Ollama qwen3-embedding:4b 로 교체.
-- qwen3-embedding:4b는 native 2560 차원이지만, MRL(Matryoshka) 학습 모델이므로
-- 애플리케이션 레벨에서 앞 1536차원으로 truncate + L2 정규화하여 사용한다.
-- 벡터 컬럼(vector(1536))은 그대로 유지.
--
-- 임베딩 공간이 완전히 다르므로 기존 임베딩 데이터는 재인덱싱 대상.

-- 기존 EMBEDDING 모델 제거
DELETE FROM llm_model WHERE purpose = 'EMBEDDING';

-- 신규 기본 EMBEDDING 모델 등록 (재실행 안전)
INSERT INTO llm_model (provider, model_id, display_name, purpose, is_default, base_url, temperature)
VALUES ('OLLAMA', 'qwen3-embedding:4b', 'Qwen3 Embedding 4B (1536d)', 'EMBEDDING', true, 'http://localhost:11434', NULL)
ON CONFLICT (provider, model_id, purpose)
DO UPDATE SET display_name = EXCLUDED.display_name,
              is_default   = EXCLUDED.is_default,
              base_url     = EXCLUDED.base_url,
              is_active    = true;

-- 기존 문서/청크 정리 (재업로드로 재인덱싱 수행)
TRUNCATE TABLE document CASCADE;
