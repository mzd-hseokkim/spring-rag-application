# Phase 5 — LLM 모델 관리 (Multi-Model Registry): 상세 설계

## 현재 상태 (Phase 4 완료)

- Chat 모델: Ollama `gpt-oss:20b` (ChatClient.Builder → 9개 클래스에서 사용)
- Embedding 모델: Ollama `qwen3-embedding:4b` (EmbeddingModel → 4개 클래스에서 사용)
- Anthropic: 의존성 존재하나 auto-config 제외 상태
- 모델 전환: application.yml 수동 변경 + 재시작 필요

### 모델 사용처 전체 목록

| 용도 | 클래스 | 현재 의존성 |
|------|--------|------------|
| CHAT | ChatService | ChatClient.Builder |
| QUERY | SearchAgent, MultiStepReasoner, QueryCompressor, QueryExpander, QueryRouter | ChatClient.Builder |
| RERANK | RerankService | ChatClient.Builder |
| EVALUATION | FaithfulnessEvaluator, RelevanceEvaluator | ChatClient.Builder |
| EMBEDDING | IngestionPipeline, VectorSearchService, SemanticChunkingStrategy, ChunkingConfig | EmbeddingModel |

---

## 1. DB 스키마

### 1-1. 모델 테이블

`V5__create_llm_model_table.sql`:

```sql
CREATE TABLE llm_model (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider      VARCHAR(20)   NOT NULL,   -- OLLAMA, ANTHROPIC
    model_id      VARCHAR(200)  NOT NULL,   -- gpt-oss:20b, claude-sonnet-4-20250514
    display_name  VARCHAR(200)  NOT NULL,   -- 화면 표시명
    purpose       VARCHAR(20)   NOT NULL,   -- CHAT, EMBEDDING, QUERY, RERANK, EVALUATION
    is_default    BOOLEAN       NOT NULL DEFAULT false,
    is_active     BOOLEAN       NOT NULL DEFAULT true,
    base_url      VARCHAR(500),             -- Ollama base URL (null이면 기본값 사용)
    api_key_ref   VARCHAR(100),             -- 환경변수명 (예: ANTHROPIC_API_KEY)
    temperature   DOUBLE PRECISION DEFAULT 0.3,
    max_tokens    INT,
    created_at    TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT now(),
    UNIQUE(provider, model_id, purpose)
);
```

**제약 조건**:
- `(provider, model_id, purpose)` 유니크 — 같은 모델을 같은 용도로 중복 등록 불가
- `is_default`는 purpose별로 최대 1개만 true

### 1-2. 초기 데이터

`V6__seed_default_models.sql`:

```sql
INSERT INTO llm_model (provider, model_id, display_name, purpose, is_default, base_url, temperature)
VALUES
  ('OLLAMA', 'gpt-oss:20b', 'GPT-OSS 20B', 'CHAT', true, 'http://localhost:11434', 0.3),
  ('OLLAMA', 'gpt-oss:20b', 'GPT-OSS 20B', 'QUERY', true, 'http://localhost:11434', 0.3),
  ('OLLAMA', 'gpt-oss:20b', 'GPT-OSS 20B', 'RERANK', true, 'http://localhost:11434', 0.3),
  ('OLLAMA', 'gpt-oss:20b', 'GPT-OSS 20B', 'EVALUATION', true, 'http://localhost:11434', 0.3),
  ('OLLAMA', 'qwen3-embedding:4b', 'Qwen3 Embedding 4B', 'EMBEDDING', true, 'http://localhost:11434', null);
```

---

## 2. 패키지 구조

```
com.example.rag.model/
├── LlmModel              (Entity)
├── LlmModelRepository
├── ModelProvider          (Enum: OLLAMA, ANTHROPIC)
├── ModelPurpose           (Enum: CHAT, EMBEDDING, QUERY, RERANK, EVALUATION)
├── LlmModelService        # 모델 CRUD + 기본 모델 관리
├── LlmModelController     # 관리 API
├── ModelClientFactory      # 모델 → ChatClient/EmbeddingModel 동적 생성
├── ModelClientProvider     # 용도별 모델 조회 + 클라이언트 반환
└── adapter/
    ├── OllamaAdapter       # Ollama 모델 검색/테스트
    └── AnthropicAdapter    # Anthropic 모델 테스트
```

---

## 3. 핵심 클래스 설계

### 3-1. Entity

```java
@Entity
@Table(name = "llm_model")
public class LlmModel {
    UUID id;
    ModelProvider provider;     // OLLAMA, ANTHROPIC
    String modelId;             // gpt-oss:20b
    String displayName;         // GPT-OSS 20B
    ModelPurpose purpose;       // CHAT, EMBEDDING, ...
    boolean isDefault;
    boolean isActive;
    String baseUrl;             // Ollama: http://localhost:11434
    String apiKeyRef;           // 환경변수명: ANTHROPIC_API_KEY
    Double temperature;
    Integer maxTokens;
}
```

### 3-2. ModelClientFactory

모델 정보로 Spring AI 클라이언트를 동적 생성하는 팩토리.

```java
@Component
public class ModelClientFactory {

    ChatClient createChatClient(LlmModel model) {
        return switch (model.getProvider()) {
            case OLLAMA -> createOllamaChatClient(model);
            case ANTHROPIC -> createAnthropicChatClient(model);
        };
    }

    EmbeddingModel createEmbeddingModel(LlmModel model) {
        // OLLAMA만 지원 (Anthropic은 임베딩 미제공)
        return createOllamaEmbeddingModel(model);
    }
}
```

**구현 방식**:
- Ollama: `OllamaApi` + `OllamaChatModel` 직접 생성 (auto-config 대신)
- Anthropic: `AnthropicApi` + `AnthropicChatModel` 직접 생성
- API 키: `model.getApiKeyRef()`로 환경변수명을 읽고, `System.getenv()`로 실제 값 조회

**캐싱**: 동일 모델에 대해 매번 클라이언트를 생성하면 비효율 → `ConcurrentHashMap<UUID, Object>`로 캐싱, 모델 수정/삭제 시 캐시 무효화

### 3-3. ModelClientProvider

용도별 기본 모델을 조회하고 클라이언트를 반환하는 서비스. 기존 `ChatClient.Builder`와 `EmbeddingModel` 주입을 대체.

```java
@Component
public class ModelClientProvider {

    ChatClient getChatClient(ModelPurpose purpose) {
        LlmModel model = modelService.getDefaultModel(purpose);
        return clientFactory.createChatClient(model);
    }

    EmbeddingModel getEmbeddingModel() {
        LlmModel model = modelService.getDefaultModel(ModelPurpose.EMBEDDING);
        return clientFactory.createEmbeddingModel(model);
    }

    // 특정 모델 ID로 직접 지정
    ChatClient getChatClient(UUID modelId) { ... }
}
```

### 3-4. 기존 클래스 변경 패턴

**Before** (현재):
```java
public class ChatService {
    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder builder, ...) {
        this.chatClient = builder.build();
    }
}
```

**After**:
```java
public class ChatService {
    private final ModelClientProvider modelProvider;

    public ChatService(ModelClientProvider modelProvider, ...) {
        this.modelProvider = modelProvider;
    }

    public ChatResponse chat(...) {
        ChatClient chatClient = modelProvider.getChatClient(ModelPurpose.CHAT);
        // ... 사용
    }
}
```

모든 9개 ChatClient.Builder 클래스와 4개 EmbeddingModel 클래스에 동일 패턴 적용.

**장점**: 매 요청마다 최신 기본 모델을 조회 → 재시작 없이 모델 전환 가능

---

## 4. 관리 API

### 4-1. 모델 CRUD

```
GET    /api/models                         # 전체 목록 (필터: ?purpose=CHAT&active=true)
POST   /api/models                         # 모델 등록
PUT    /api/models/{id}                    # 모델 수정
DELETE /api/models/{id}                    # 모델 삭제

PATCH  /api/models/{id}/activate           # 활성화
PATCH  /api/models/{id}/deactivate         # 비활성화
PATCH  /api/models/{id}/set-default        # 기본 모델 지정 (같은 purpose의 기존 default 해제)
```

### 4-2. 모델 테스트

```
POST /api/models/{id}/test
Response: { "success": true, "latencyMs": 230, "message": "OK" }
         { "success": false, "message": "Connection refused" }
```

- Ollama: `GET {baseUrl}/api/tags` → 모델 존재 확인
- Anthropic: `POST /v1/messages` with 짧은 메시지 → 응답 확인

### 4-3. Ollama 자동 검색

```
GET /api/models/discover/ollama
Response: [
  { "modelId": "gpt-oss:20b", "size": "13GB", "modifiedAt": "..." },
  { "modelId": "qwen3-embedding:4b", "size": "2.5GB", "modifiedAt": "..." }
]
```

Ollama API `GET /api/tags` 호출 → 설치된 모델 목록 반환. 아직 등록되지 않은 모델은 "등록 가능" 표시.

---

## 5. 프론트엔드

### 5-1. 모델 관리 UI

사이드바에 "설정" 탭 추가 → 모델 관리 화면:

```
┌─────────────────────────────┐
│  모델 관리                   │
│  ┌─────────────────────────┐│
│  │ 용도: [CHAT ▼]          ││
│  └─────────────────────────┘│
│                              │
│  ● GPT-OSS 20B (기본)       │
│    Ollama · gpt-oss:20b     │
│    [테스트] [기본 설정]      │
│                              │
│  ○ Claude Sonnet 4          │
│    Anthropic · claude-...   │
│    [테스트] [기본 설정]      │
│                              │
│  [+ 모델 추가]               │
│                              │
│  [Ollama 모델 자동 검색]     │
└─────────────────────────────┘
```

### 5-2. 채팅 헤더에 모델 선택

채팅 화면 상단에 현재 CHAT 모델 표시 + 변경 가능:

```
┌────────────────────────────────────┐
│ 채팅    [GPT-OSS 20B ▼]  [새 대화] │
└────────────────────────────────────┘
```

### 5-3. 컴포넌트 구조

```
src/components/
├── model/
│   ├── ModelManagement.tsx    # 모델 관리 전체 화면
│   ├── ModelList.tsx          # 용도별 모델 목록
│   ├── ModelForm.tsx          # 모델 추가/수정 폼
│   └── ModelDiscovery.tsx     # Ollama 자동 검색
├── chat/
│   └── ModelSelector.tsx      # 채팅 헤더 모델 선택 드롭다운
```

---

## 6. Spring AI Auto-Config 제거

현재 Spring AI가 auto-config으로 `OllamaChatModel`, `OllamaEmbeddingModel`을 자동 생성. Phase 5에서는 `ModelClientFactory`가 직접 생성하므로 auto-config을 비활성화.

**RagApplication.java 변경**:

```java
@SpringBootApplication(exclude = {
    AnthropicChatAutoConfiguration.class,
    OllamaChatAutoConfiguration.class,
    OllamaEmbeddingAutoConfiguration.class
})
```

**application.yml**: `spring.ai.ollama.*` 설정 → `app.model.*` 설정으로 이동 (기본 Ollama URL 등)

```yaml
app:
  model:
    ollama:
      default-base-url: http://localhost:11434
```

---

## 7. 구현 순서

### Step 1: DB + Entity + 관리 API

1. Flyway 마이그레이션 (llm_model 테이블 + 초기 데이터)
2. LlmModel entity, ModelProvider/ModelPurpose enum
3. LlmModelRepository, LlmModelService
4. LlmModelController (CRUD + set-default)
5. **검증**: API로 모델 CRUD, 기본 모델 지정 동작 확인

### Step 2: ModelClientFactory + Provider

1. ModelClientFactory 구현 (Ollama/Anthropic ChatClient, EmbeddingModel 동적 생성)
2. ModelClientProvider 구현 (용도별 기본 모델 조회 + 클라이언트 반환)
3. 기존 9개 ChatClient.Builder 클래스 → ModelClientProvider로 교체
4. 기존 4개 EmbeddingModel 클래스 → ModelClientProvider로 교체
5. Spring AI auto-config 제외
6. **검증**: 기존 기능이 모두 동작하는지 확인 (회귀 테스트)

### Step 3: 모델 테스트 + Ollama 검색

1. OllamaAdapter (모델 검색, 연결 테스트)
2. AnthropicAdapter (연결 테스트)
3. 테스트/검색 API 엔드포인트
4. **검증**: Ollama 모델 자동 검색, 연결 테스트 동작 확인

### Step 4: 프론트엔드

1. 모델 관리 UI (목록, 추가, 테스트, 기본 설정)
2. 채팅 헤더 모델 선택 드롭다운
3. 사이드바 탭 추가 (문서 관리 / 모델 관리)
4. **검증**: 브라우저에서 모델 등록 → 기본 설정 → 채팅에서 모델 전환 확인

---

## 8. 핵심 의사결정 요약

| 결정 | 선택 | 이유 |
|------|------|------|
| 클라이언트 생성 | 팩토리 패턴 (직접 생성) | Spring AI auto-config 대신 DB 기반 동적 생성 필요 |
| 클라이언트 캐싱 | ConcurrentHashMap | 매 요청마다 생성은 비효율, 모델 변경 시 캐시 무효화 |
| 모델 전환 | 매 요청 시 기본 모델 조회 | 재시작 없이 실시간 전환 가능 |
| API 키 관리 | 환경변수 참조 (apiKeyRef) | DB에 키 직접 저장하지 않음, 보안 |
| Anthropic 임베딩 | 미지원 | Anthropic이 임베딩 모델을 제공하지 않음 |
| 초기 데이터 | Flyway seed migration | 기존 동작을 유지하면서 마이그레이션 |
