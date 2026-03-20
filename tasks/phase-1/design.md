# Phase 1 — Core RAG Pipeline: 상세 설계

## 현재 상태

- 백엔드: `RagApplication.java`만 존재 (스켈레톤)
- 프론트엔드: Vite + React 19 템플릿 상태
- DB: pgvector 확장 생성 마이그레이션 (`V1__init.sql`)만 존재
- 인프라: PostgreSQL(pgvector) + Redis docker-compose 준비됨
- 의존성: Spring AI 1.1.3, spring-ai-starter-model-anthropic, spring-ai-starter-model-ollama 포함

---

## 1. 백엔드 패키지 구조

도메인 기반(feature 단위) 패키지 구성:

```
com.example.rag/
├── document/               # 문서 수집 및 처리
│   ├── DocumentController
│   ├── DocumentService
│   ├── DocumentRepository
│   ├── Document              (Entity)
│   ├── DocumentChunk         (Entity)
│   ├── DocumentStatus        (Enum)
│   ├── parser/
│   │   ├── DocumentParser      (Interface)
│   │   ├── PdfDocumentParser
│   │   ├── TextDocumentParser
│   │   └── MarkdownDocumentParser
│   └── pipeline/
│       ├── IngestionPipeline
│       └── ChunkingStrategy
├── search/                 # 하이브리드 검색
│   ├── SearchService
│   ├── VectorSearchService
│   ├── KeywordSearchService
│   └── RrfFusionService
├── chat/                   # 응답 생성
│   ├── ChatController
│   └── ChatService
└── common/                 # 공통 설정/유틸
    └── config/
        ├── AiConfig
        └── AsyncConfig
```

---

## 2. 데이터 모델 및 DB 스키마

### 2-1. Entity 설계

**Document** — 업로드된 문서 메타데이터

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | UUID (PK) | 문서 고유 ID |
| filename | VARCHAR(500) | 원본 파일명 |
| content_type | VARCHAR(100) | MIME 타입 |
| file_size | BIGINT | 파일 크기 (bytes) |
| status | VARCHAR(20) | PENDING / PROCESSING / COMPLETED / FAILED |
| error_message | TEXT | 실패 시 에러 메시지 |
| chunk_count | INT | 생성된 청크 수 |
| created_at | TIMESTAMP | 업로드 일시 |
| updated_at | TIMESTAMP | 최종 수정 일시 |

**DocumentChunk** — 문서 청크 + 임베딩

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | UUID (PK) | 청크 고유 ID |
| document_id | UUID (FK) | 소속 문서 ID |
| content | TEXT | 청크 원본 텍스트 |
| chunk_index | INT | 문서 내 순서 |
| embedding | vector(2560) | Ollama qwen3-embedding:4b 벡터 |
| content_tsv | tsvector | Full-Text Search용 |
| created_at | TIMESTAMP | 생성 일시 |

### 2-2. Flyway 마이그레이션

`V2__create_document_tables.sql`:

```sql
CREATE TABLE document (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename      VARCHAR(500)  NOT NULL,
    content_type  VARCHAR(100)  NOT NULL,
    file_size     BIGINT        NOT NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    chunk_count   INT           NOT NULL DEFAULT 0,
    created_at    TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE TABLE document_chunk (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id   UUID          NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    content       TEXT          NOT NULL,
    chunk_index   INT           NOT NULL,
    embedding     vector(2560),
    content_tsv   tsvector,
    created_at    TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX idx_chunk_document_id ON document_chunk(document_id);
CREATE INDEX idx_chunk_content_tsv ON document_chunk USING gin (content_tsv);
```

> **참고**: 2560 차원은 ivfflat(2000), hnsw(2000) 인덱스 제한을 초과한다. MVP 단계에서는 인덱스 없이 exact search를 사용한다 (데이터 소량). Phase 2에서 halfvec 또는 차원 축소 기반 인덱스를 도입한다.

### 2-3. Spring AI의 VectorStore와의 관계

Spring AI의 `PgVectorStore`는 자체 테이블(`vector_store`)을 자동 생성한다. 하지만 우리는 **직접 관리하는 `document_chunk` 테이블**을 사용한다. 이유:
- 문서-청크 간 FK 관계 필요
- 키워드 검색(tsvector)을 같은 테이블에서 수행
- 문서 삭제 시 청크 CASCADE 삭제

따라서 `spring-ai-starter-vector-store-pgvector` 의존성을 제거하고, **직접 pgvector 쿼리(Native Query)** 를 사용한다.

---

## 3. 문서 수집 및 처리 (Document Ingestion)

### 3-1. 문서 업로드 API

```
POST /api/documents
Content-Type: multipart/form-data
Body: file (MultipartFile)

Response: 202 Accepted
{
  "id": "uuid",
  "filename": "example.pdf",
  "contentType": "application/pdf",
  "fileSize": 102400,
  "status": "PENDING",
  "createdAt": "2026-03-19T10:00:00"
}
```

**처리 흐름:**
1. `DocumentController`가 파일 수신
2. `DocumentService.upload()`:
   - Document 엔티티 생성 (status=PENDING)
   - DB 저장
   - `@Async`로 `IngestionPipeline.process(document, fileBytes)` 호출
3. 즉시 202 Accepted + Document 메타데이터 반환

### 3-2. 문서 목록/상태 조회 API

```
GET /api/documents
Response: 200 OK
[{ id, filename, status, chunkCount, createdAt }, ...]

GET /api/documents/{id}
Response: 200 OK
{ id, filename, contentType, fileSize, status, errorMessage, chunkCount, createdAt }
```

### 3-3. 비동기 처리 파이프라인 (`IngestionPipeline`)

```
fileBytes → parse(contentType) → chunk(text) → embed(chunks) → save(chunks) → updateStatus
```

**단계별 상세:**

1. **파싱** — `DocumentParser` 인터페이스, 파일 타입별 구현체
   - PDF: Apache PDFBox (`org.apache.pdfbox:pdfbox`) — 의존성 추가 필요
   - TXT: 직접 UTF-8 디코딩
   - Markdown: 그대로 텍스트 사용 (마크다운 문법 유지)

2. **청킹** — `ChunkingStrategy`
   - 고정 크기 + 오버랩 방식 (시맨틱 청킹은 Phase 2에서 고도화)
   - 기본값: chunk_size=1000자, overlap=200자
   - 문단 경계를 최대한 존중 (줄바꿈 2개 = 문단 구분)

3. **임베딩 생성** — Spring AI의 `EmbeddingModel` 사용
   - `spring-ai-starter-model-ollama`가 제공하는 `OllamaEmbeddingModel` 자동 주입
   - 배치 임베딩: 한 번에 여러 청크를 임베딩 요청
   - 모델: `qwen3-embedding:4b` (2560 dimensions, 로컬 Ollama)

4. **저장** — `DocumentChunkRepository`
   - 청크 텍스트 + 임베딩 벡터 + tsvector 저장
   - tsvector 생성: `to_tsvector('simple', content)` (한국어+영어 혼용 고려하여 `simple` config)

5. **상태 관리** — Document status 업데이트
   - 성공: PENDING → PROCESSING → COMPLETED + chunk_count 업데이트
   - 실패: → FAILED + error_message 저장

### 3-4. 처리 상태 SSE (`DocumentController`)

```
GET /api/documents/{id}/events
Accept: text/event-stream

event: status
data: {"status": "PROCESSING", "progress": "파싱 중..."}

event: status
data: {"status": "COMPLETED", "chunkCount": 15}
```

**구현 방식:**
- `SseEmitter`를 사용한 심플한 구현
- `IngestionPipeline`이 진행 상태를 `SseEmitterRegistry`에 발행
- 클라이언트가 SSE 연결 후 상태 변경 수신
- 처리 완료/실패 시 emitter 종료

---

## 4. 하이브리드 검색 (Hybrid Retrieval)

### 4-1. 검색 API

```
내부 호출 (ChatService → SearchService)
Input: String query, int topK (default: 5)
Output: List<ChunkSearchResult> — (chunkId, documentId, filename, content, score)
```

검색은 직접 API로 노출하지 않고, ChatService에서 내부 호출한다.

### 4-2. 벡터 검색 (`VectorSearchService`)

```sql
SELECT c.id, c.document_id, c.content, c.chunk_index,
       d.filename,
       1 - (c.embedding <=> :queryEmbedding) AS similarity
FROM document_chunk c
JOIN document d ON d.id = c.document_id
WHERE d.status = 'COMPLETED'
ORDER BY c.embedding <=> :queryEmbedding
LIMIT :limit
```

- 쿼리를 임베딩 → pgvector cosine distance(`<=>`) 연산자로 유사도 검색
- `EmbeddingModel.embed(query)`로 쿼리 벡터 생성

### 4-3. 키워드 검색 (`KeywordSearchService`)

```sql
SELECT c.id, c.document_id, c.content, c.chunk_index,
       d.filename,
       ts_rank(c.content_tsv, query) AS rank
FROM document_chunk c
JOIN document d ON d.id = c.document_id,
     plainto_tsquery('simple', :query) query
WHERE d.status = 'COMPLETED'
  AND c.content_tsv @@ query
ORDER BY rank DESC
LIMIT :limit
```

- `plainto_tsquery('simple', query)`: 사용자 입력을 토큰화하여 tsquery 생성
- `simple` config: 형태소 분석 없이 공백 기준 토큰화 (한국어 호환)

### 4-4. RRF 퓨전 (`RrfFusionService`)

```
RRF_score(d) = Σ 1 / (k + rank_i(d))
```

- `k = 60` (표준 RRF 상수)
- 벡터 검색과 키워드 검색 결과를 각각 rank 기반으로 점수 계산
- 합산 점수로 정렬, 상위 K건 반환
- 중복 제거: 같은 chunk_id가 양쪽에 있으면 합산

**병렬 실행:**
- `CompletableFuture`를 사용하여 벡터 검색과 키워드 검색을 병렬 실행
- 두 결과를 `RrfFusionService`에서 병합

---

## 5. 응답 생성 (Generation)

### 5-1. 채팅 API

```
POST /api/chat
Content-Type: application/json
Accept: text/event-stream

Request:
{
  "message": "사용자 질문 내용"
}

Response: SSE Stream
event: token
data: {"content": "답변"}

event: token
data: {"content": " 토큰들이"}

event: sources
data: [{"documentId": "uuid", "filename": "doc.pdf", "chunkIndex": 2, "excerpt": "관련 내용..."}]

event: done
data: {}
```

### 5-2. ChatService 처리 흐름

```
1. 사용자 메시지 수신
2. SearchService.search(message, topK=5) — 하이브리드 검색
3. 프롬프트 구성:
   - 시스템 프롬프트 + 검색된 컨텍스트 + 사용자 질문
4. ChatClient.prompt().stream() — Spring AI 스트리밍 호출
5. SSE로 토큰 단위 전송
6. 마지막에 sources 이벤트로 출처 정보 전송
```

### 5-3. 프롬프트 템플릿

```
당신은 주어진 문서를 기반으로 질문에 답변하는 도우미입니다.

아래 문서 내용을 참고하여 질문에 답변하세요.
문서에 없는 내용은 "제공된 문서에서 해당 정보를 찾을 수 없습니다"라고 답변하세요.

---
[컨텍스트]
{context}
---

질문: {question}
```

### 5-4. 스트리밍 응답 구현

- Spring AI의 `ChatClient.prompt().stream()` → `Flux<String>` 반환
- `SseEmitter` 또는 Spring WebFlux의 `Flux`를 SSE로 변환
- Spring MVC 환경이므로 `SseEmitter`를 사용하여 `Flux`를 구독하고 토큰별 전송

> **의사결정**: spring-boot-starter-web(MVC)을 사용 중이므로 WebFlux 의존성을 추가하지 않고, `SseEmitter` + `Flux.subscribe()`로 스트리밍을 처리한다. Spring AI는 내부적으로 Project Reactor를 사용하므로 `Flux` 반환이 가능하다.

---

## 6. 프론트엔드 설계

### 6-1. 기술 선택

- 추가 의존성: 없음 (순수 React + fetch API)
- 스타일링: CSS Modules 또는 인라인 스타일 (별도 라이브러리 없이 시작)

### 6-2. 컴포넌트 구조

```
src/
├── App.tsx                 # 레이아웃 (사이드바 + 메인)
├── components/
│   ├── chat/
│   │   ├── ChatView.tsx       # 채팅 화면 전체
│   │   ├── MessageList.tsx    # 메시지 목록
│   │   ├── MessageItem.tsx    # 개별 메시지 (user/assistant)
│   │   ├── ChatInput.tsx      # 입력창 + 전송 버튼
│   │   └── SourceList.tsx     # 출처 표시
│   └── document/
│       ├── DocumentUpload.tsx # 파일 업로드 (드래그앤드롭)
│       └── DocumentList.tsx   # 문서 목록 + 상태 표시
├── hooks/
│   ├── useChat.ts             # 채팅 SSE 스트리밍 훅
│   ├── useDocuments.ts        # 문서 목록 조회 훅
│   └── useDocumentUpload.ts   # 업로드 + SSE 상태 훅
├── types/
│   └── index.ts               # 타입 정의
└── api/
    └── client.ts              # fetch 래퍼
```

### 6-3. SSE 스트리밍 처리 (`useChat`)

```typescript
// 핵심 로직 스케치
const response = await fetch('/api/chat', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ message }),
});

const reader = response.body.getReader();
const decoder = new TextDecoder();

// SSE 파싱하여 토큰별 상태 업데이트
while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  const text = decoder.decode(value);
  // SSE 이벤트 파싱 → state 업데이트
}
```

### 6-4. 화면 레이아웃

```
┌─────────────────────────────────────────┐
│  📄 문서 관리           │   💬 채팅      │
│  ┌───────────────────┐  │               │
│  │ [파일 드래그앤드롭]│  │  메시지 목록   │
│  └───────────────────┘  │  ...          │
│                         │  ...          │
│  문서 목록              │               │
│  • doc1.pdf ✅          │  [출처: doc1] │
│  • doc2.txt ⏳          │               │
│                         │  ┌──────────┐ │
│                         │  │ 입력창   │ │
│                         │  └──────────┘ │
└─────────────────────────────────────────┘
```

---

## 7. 추가 의존성

### Backend (`build.gradle.kts`에 추가)

```kotlin
implementation("org.apache.pdfbox:pdfbox:3.0.4")        // PDF 파싱
```

> Spring AI starter가 이미 포함하는 것: Ollama 클라이언트, Anthropic 클라이언트, Reactor

### Frontend (`package.json`에 추가)

- 추가 의존성 없음. fetch API + EventSource로 충분.

---

## 8. 설정 추가 (`application.yml`)

```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}
      chat:
        enabled: false           # 현재 Ollama 사용, 필요 시 전환
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: gpt-oss:20b
          temperature: 0.3
      embedding:
        options:
          model: qwen3-embedding:4b
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB

app:
  chunking:
    chunk-size: 1000
    overlap: 200
  search:
    top-k: 5
    rrf-k: 60
```

---

## 9. 비동기 처리 설정

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean
    public TaskExecutor ingestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ingestion-");
        return executor;
    }
}
```

- `@Async("ingestionExecutor")`로 문서 처리 파이프라인 비동기 실행
- 최대 4개 문서 동시 처리

---

## 10. 구현 순서 (단계별)

전체를 4단계로 나누어 점진적으로 구현한다. 각 단계 끝에 동작을 검증한다.

### Step 1: 문서 업로드 + 저장 기반

1. Flyway 마이그레이션 (`V2__create_document_tables.sql`)
2. Entity 클래스 (`Document`, `DocumentChunk`, `DocumentStatus`)
3. Repository 인터페이스
4. 문서 업로드 API (`POST /api/documents`) — 파일 수신 + 메타데이터 저장
5. 문서 조회 API (`GET /api/documents`, `GET /api/documents/{id}`)
6. **검증**: API 호출로 문서 업로드 후 DB에 메타데이터 저장 확인

### Step 2: 비동기 파이프라인 (파싱 → 청킹 → 임베딩 → 저장)

1. AsyncConfig 설정
2. DocumentParser 구현 (PDF, TXT, MD)
3. ChunkingStrategy 구현
4. IngestionPipeline 구현 (EmbeddingModel 활용)
5. 상태 관리 (PENDING → PROCESSING → COMPLETED/FAILED)
6. SSE 상태 알림
7. **검증**: 문서 업로드 후 자동으로 청크 생성 + 임베딩 저장 확인

### Step 3: 하이브리드 검색 + 응답 생성

1. VectorSearchService (Native Query)
2. KeywordSearchService (Native Query)
3. RrfFusionService
4. ChatService + 프롬프트 구성
5. ChatController (SSE 스트리밍)
6. **검증**: 문서 업로드 후 질문에 대해 관련 내용 기반 답변 확인

### Step 4: 프론트엔드

1. 프로젝트 구조 정리 (기존 템플릿 코드 제거)
2. 채팅 UI (메시지 입력 → SSE 스트리밍 표시)
3. 문서 업로드 UI (드래그앤드롭 + 상태 표시)
4. 출처 표시 UI
5. **검증**: 브라우저에서 end-to-end 동작 확인

---

## 11. 핵심 의사결정 요약

| 결정 | 선택 | 이유 |
|------|------|------|
| Vector 테이블 | 직접 관리 (PgVectorStore 미사용) | FK 관계, tsvector 통합, CASCADE 삭제 필요 |
| 임베딩 모델 | Ollama qwen3-embedding:4b (2560d) | 로컬 실행, 한국어 우수, API 키 불필요 |
| LLM 모델 | Ollama gpt-oss:20b (로컬) | 로컬 실행, 빠른 반복 개발에 적합. 필요 시 Anthropic Claude로 전환 가능 |
| 청킹 전략 | 고정 크기 + 오버랩 | MVP 단순성, Phase 2에서 시맨틱 청킹 도입 |
| FTS config | `simple` | 한국어+영어 혼용 환경, 형태소 분석 없이 동작 |
| 스트리밍 | SseEmitter (MVC) | WebFlux 의존성 추가 없이 기존 MVC 활용 |
| 프론트엔드 상태관리 | React useState/useReducer | 추가 라이브러리 없이 최소한으로 |
| PDF 파싱 | Apache PDFBox 3.x | 표준적, 안정적 |
