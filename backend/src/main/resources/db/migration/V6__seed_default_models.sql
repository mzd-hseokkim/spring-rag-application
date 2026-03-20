INSERT INTO llm_model (provider, model_id, display_name, purpose, is_default, base_url, temperature)
VALUES
    ('OLLAMA', 'gpt-oss:20b', 'GPT-OSS 20B', 'CHAT', true, 'http://localhost:11434', 0.3),
    ('OLLAMA', 'gpt-oss:20b', 'GPT-OSS 20B', 'QUERY', true, 'http://localhost:11434', 0.3),
    ('OLLAMA', 'gpt-oss:20b', 'GPT-OSS 20B', 'RERANK', true, 'http://localhost:11434', 0.3),
    ('OLLAMA', 'gpt-oss:20b', 'GPT-OSS 20B', 'EVALUATION', true, 'http://localhost:11434', 0.3),
    ('OLLAMA', 'qwen3-embedding:4b', 'Qwen3 Embedding 4B', 'EMBEDDING', true, 'http://localhost:11434', null);
