# Phase 3 — LLMOps + Quality Assurance: 상세 설계

## 현재 상태 (Phase 2 완료)

- 파이프라인: 쿼리 압축 → 라우팅 → 확장 → 검색 → 퓨전 → 리랭킹 → LLM 응답
- 대화 관리: Redis 세션 이력
- 모델: Ollama gpt-oss:20b (Chat), qwen3-embedding:4b (Embedding)
- 로깅/모니터링: 없음 (기본 Spring Boot 로그만)

---

## 전체 구조

Phase 3은 **운영 기반**을 구축하는 단계다. 기능 추가보다는 기존 파이프라인에 횡단 관심사(cross-cutting concerns)를 입히는 작업이다.

```
요청 진입
  ↓
[가드레일] Rate Limiting → 입력 검증
  ↓
[기존 파이프라인] (Phase 1~2)
  ↓ (각 단계마다)
[트레이싱] 단계별 latency + 입출력 기록
  ↓
응답 반환
  ↓ (비동기)
[평가] Faithfulness/Relevance 샘플링 평가
[피드백] 사용자 피드백 수집
```

---

## 1. 관측성 (Observability)

### 1-1. 패키지 구조

```
com.example.rag.observability/
├── PipelineTracer          # 단계별 latency/로그 수집
├── TraceContext            # 요청 단위 trace 컨텍스트
└── TokenUsageTracker       # 토큰 사용량 추적
```

### 1-2. 파이프라인 트레이싱 (`PipelineTracer`)

**목적**: 요청 한 건이 파이프라인의 각 단계를 거치면서 걸린 시간과 결과를 기록

**TraceContext** — 요청 단위로 생성, ThreadLocal 또는 파라미터로 전달:

```java
record TraceContext(
    String traceId,         // UUID
    String sessionId,
    String originalQuery,
    Instant startTime,
    List<TraceStep> steps   // 단계별 기록
)

record TraceStep(
    String name,            // "compress", "route", "expand", "search", "rerank", "generate"
    Instant startTime,
    Instant endTime,
    Map<String, Object> metadata  // 단계별 추가 정보
)
```

**적용 방식**:
- `ChatService.chat()`에서 `TraceContext` 생성
- 각 단계 호출 전후로 `tracer.start("step") / tracer.end("step", metadata)` 호출
- 응답 완료 후 전체 trace를 로그로 출력 (JSON 구조화 로깅)

**로그 출력 형태** (Structured JSON):

```json
{
  "traceId": "abc-123",
  "sessionId": "session-456",
  "query": "기술 스택이 뭐야?",
  "route": "RAG",
  "totalLatencyMs": 3200,
  "steps": [
    { "name": "compress", "latencyMs": 450, "skipped": false },
    { "name": "route", "latencyMs": 380, "result": "RAG" },
    { "name": "expand", "latencyMs": 520, "queryCount": 4 },
    { "name": "search", "latencyMs": 180, "resultCount": 15 },
    { "name": "rerank", "latencyMs": 1200, "candidateCount": 15, "resultCount": 5 },
    { "name": "generate", "latencyMs": 470, "tokenCount": 256 }
  ]
}
```

### 1-3. 토큰 사용량 추적

**접근 방식**: Spring AI의 `ChatResponse` 메타데이터에서 usage 정보 추출

- Ollama는 응답에 `eval_count`, `prompt_eval_count`를 반환
- 스트리밍 모드에서는 마지막 청크에 usage가 포함될 수 있음
- 추출 불가 시 대략적 추정 (입력 글자 수 / 4 ≈ 토큰 수)

**저장**: trace 로그에 포함. 별도 DB 저장은 Phase 4에서 고려.

### 1-4. 검색 품질 로깅

트레이싱의 search/rerank 단계 metadata에 포함:
- 검색된 chunk ID 목록 + 각 점수
- 리랭킹 전후 순위 변화
- 키워드 검색 히트 수 vs 벡터 검색 히트 수

---

## 2. 평가 (Evaluation)

### 2-1. 평가 전략

**원칙**: 전수 평가는 비용이 과도하므로, **샘플링 기반**으로 실행한다.

- 요청의 N%만 평가 (기본값: 10%)
- 평가는 응답 반환 후 비동기로 실행
- 결과는 로그로 기록 (DB 저장은 Phase 4)

### 2-2. Faithfulness 평가 (`FaithfulnessEvaluator`)

**위치**: `com.example.rag.evaluation/FaithfulnessEvaluator`

**목적**: LLM 응답이 제공된 컨텍스트에 근거하는지 검증 (환각 탐지)

**프롬프트**:

```
아래 컨텍스트와 답변이 주어졌을 때, 답변이 컨텍스트에 근거하는지 평가하세요.
"FAITHFUL" 또는 "NOT_FAITHFUL" 중 하나만 답하세요.

[컨텍스트]
{context}

[답변]
{response}
```

### 2-3. Relevance 평가 (`RelevanceEvaluator`)

**위치**: `com.example.rag.evaluation/RelevanceEvaluator`

**목적**: 답변이 질문에 대한 적절한 답변인지 평가

**프롬프트**:

```
아래 질문과 답변이 주어졌을 때, 답변이 질문에 적절한지 1~5 점수로 평가하세요.
숫자만 답하세요.

[질문]
{query}

[답변]
{response}
```

### 2-4. 사용자 피드백

**API**:

```
POST /api/chat/feedback
{
  "sessionId": "uuid",
  "messageIndex": 3,
  "rating": "up" | "down"
}
```

**저장**: Redis hash `feedback:{sessionId}:{messageIndex}` → rating, TTL 7일

**프론트엔드**: 각 assistant 메시지 하단에 thumbs up/down 버튼 추가

---

## 3. 프롬프트 관리

### 3-1. 프롬프트 외부화

**현재 문제**: 시스템 프롬프트가 Java 코드에 하드코딩되어 있음

**변경**:
- `src/main/resources/prompts/` 디렉토리에 프롬프트 파일 분리
- `rag-system.txt`, `general-system.txt`, `compress.txt`, `expand.txt`, `route.txt`, `rerank.txt`
- `@Value("classpath:prompts/rag-system.txt")` 또는 ResourceLoader로 로딩

### 3-2. 버전 추적

- 프롬프트 파일에 주석으로 버전 명시: `# v1.0 - 2026-03-20`
- 트레이싱 로그에 사용된 프롬프트 버전 기록
- A/B 테스트는 Phase 4에서 고려 (현재는 단일 버전)

---

## 4. 가드레일

### 4-1. Rate Limiting (`RateLimiter`)

**위치**: `com.example.rag.common.guard/RateLimiter`

**구현**: Redis sliding window counter

```
Key: ratelimit:{sessionId}:{minute}
INCR + EXPIRE 60초
임계치 초과 시 429 Too Many Requests 반환
```

**기본값**: 세션당 분당 10회

**적용 위치**: `ChatController`에서 `chatService.chat()` 호출 전 체크

### 4-2. 입력 검증 (`InputValidator`)

**위치**: `com.example.rag.common.guard/InputValidator`

**검증 항목**:
- 최대 입력 길이: 2000자 (초과 시 400 Bad Request)
- 빈 메시지 거부
- 프롬프트 인젝션 패턴 필터링 (기본적인 패턴만):
  - `"ignore previous instructions"`, `"system:"`, `"<|im_start|>"` 등

**적용 위치**: `ChatController`에서 요청 수신 즉시

### 4-3. 비용 대시보드

- Phase 3에서는 로그 기반 집계만 구현
- 실제 대시보드 UI는 Phase 4에서 고려
- 로컬 Ollama 사용 시 API 비용은 0이므로, latency/처리량 중심으로 기록

---

## 5. 설정 추가 (`application.yml`)

```yaml
app:
  observability:
    trace-enabled: true
  evaluation:
    sample-rate: 0.1         # 10% 샘플링
  guard:
    rate-limit-per-minute: 10
    max-input-length: 2000
```

---

## 6. 구현 순서

### Step 1: 가드레일 (Rate Limiting + 입력 검증)

1. InputValidator 구현
2. RateLimiter 구현 (Redis)
3. ChatController에 적용
4. **검증**: 빈 메시지/긴 메시지 거부, 연속 요청 시 429 반환

### Step 2: 파이프라인 트레이싱

1. TraceContext, PipelineTracer 구현
2. ChatService 각 단계에 트레이싱 적용
3. 구조화 JSON 로그 출력
4. **검증**: 요청 시 콘솔에 단계별 latency 로그 확인

### Step 3: 프롬프트 외부화

1. `resources/prompts/` 디렉토리에 프롬프트 파일 분리
2. 각 서비스에서 파일 로딩으로 변경
3. **검증**: 프롬프트 파일 수정 후 재시작하면 반영되는지 확인

### Step 4: 평가 + 사용자 피드백

1. FaithfulnessEvaluator, RelevanceEvaluator 구현
2. ChatService 응답 완료 후 비동기 평가 호출
3. 피드백 API + 프론트엔드 thumbs up/down
4. **검증**: 로그에 평가 결과 출력 확인, 피드백 저장 확인

---

## 7. 핵심 의사결정 요약

| 결정 | 선택 | 이유 |
|------|------|------|
| 트레이싱 구현 | 자체 구현 (TraceContext) | Micrometer/Zipkin 등 외부 도구 도입 없이 단순하게 시작 |
| 평가 방식 | 샘플링 (10%) + 비동기 | 전수 평가는 LLM 호출 비용 과다 |
| Rate Limiting | Redis sliding window | 이미 Redis 사용 중, 구현 단순 |
| 프롬프트 관리 | 파일 기반 외부화 | DB 관리보다 단순, Git으로 버전 추적 가능 |
| 비용 추적 | 로그 기반 | 로컬 Ollama는 API 비용 0, latency 중심 모니터링 |
| 피드백 저장 | Redis (TTL 7일) | 별도 DB 테이블 없이 경량 구현 |
