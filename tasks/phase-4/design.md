# Phase 4 — Advanced Enhancement: 상세 설계

## 현재 상태 (Phase 3 완료)

- 청킹: 고정 크기(1000자) + 오버랩(200자), 문단 경계 존중
- 검색: 쿼리 확장 → 하이브리드 검색 → RRF 퓨전 → LLM 리랭킹
- 라우팅: LLM 기반 RAG/GENERAL 분류
- Entity: `DocumentChunk`에 content, chunkIndex, embedding, content_tsv
- 파이프라인: 파싱 → 고정 크기 청킹 → 임베딩 → 저장

---

## 1. 시맨틱 청킹 (Semantic Chunking)

기존 고정 크기 청킹을 **의미 변화 기반 분할**로 교체한다.

### 1-1. 알고리즘

```
1. 텍스트 → 문장 단위 분리
2. 각 문장을 인접 문장과 묶어서 임베딩 (buffer)
3. 인접 임베딩 간 코사인 거리 계산
4. 거리가 급격히 높은 지점(breakpoint)에서 분할
5. 최소/최대 크기 제약 적용
```

**상세 과정:**

**Step 1 — 문장 분리**: `.`, `!`, `?` + 공백 기준 분리. 약어(Mr., Dr. 등)는 예외 처리.

**Step 2 — Buffer 임베딩**: 각 문장 `i`를 단독이 아닌 전후 문장과 묶어서 임베딩.
- `buffer_size = 1`: `문장[i-1] + 문장[i] + 문장[i+1]`을 합쳐서 임베딩
- 짧은 문장의 노이즈를 줄이고 맥락을 보존
- 전체 문장을 배치로 한 번에 임베딩 (`EmbeddingModel.embed(List<String>)`)

**Step 3 — 코사인 거리**: 인접 쌍 `(i, i+1)`의 `1 - cosineSimilarity` 계산 → n-1개 거리값 배열

**Step 4 — Breakpoint 탐지**: Percentile 방식 사용
- 거리값 배열에서 X번째 백분위 이상인 지점을 breakpoint로 결정
- 기본값: **90th percentile** (조정 가능)

**Step 5 — 크기 제약**:
- 최소 200자 미만 → 다음 청크에 병합
- 최대 1500자 초과 → 고정 크기(500자, 오버랩 100자)로 2차 분할

### 1-2. 파라미터

```yaml
app:
  chunking:
    mode: semantic              # "fixed" 또는 "semantic"
    semantic:
      buffer-size: 1            # 전후 N문장 묶음
      breakpoint-percentile: 90 # 분할 임계치
      min-chunk-size: 200       # 최소 청크 크기 (자)
      max-chunk-size: 1500      # 최대 청크 크기 (자)
    fixed:                      # fallback / 2차 분할용
      chunk-size: 500
      overlap: 100
```

### 1-3. 구현 (`SemanticChunkingStrategy`)

**위치**: `com.example.rag.document.pipeline/SemanticChunkingStrategy`

**인터페이스**: 기존 `ChunkingStrategy`와 동일한 `List<String> chunk(String text)` 시그니처 유지

**의존성**: `EmbeddingModel` (문장 임베딩용)

```java
public class SemanticChunkingStrategy {

    // 1. splitSentences(text) → List<String>
    // 2. buildBufferedSentences(sentences, bufferSize) → List<String>
    // 3. embedBatch(buffered) → float[][]
    // 4. computeDistances(embeddings) → double[]
    // 5. findBreakpoints(distances, percentile) → List<Integer>
    // 6. formChunks(sentences, breakpoints) → List<String>
    // 7. enforceMinMax(chunks) → List<String>
}
```

**문장 분리 정규식**:
```java
Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z가-힣\"'])");
```
- 한국어 문장도 지원 (마침표 + 공백 + 한글/영문 대문자)
- 줄바꿈 2개(`\n\n`)도 문장 경계로 취급

**코사인 유사도 계산**: 직접 구현 (외부 라이브러리 불필요)

```java
double cosine(float[] a, float[] b) {
    double dot = 0, normA = 0, normB = 0;
    for (int i = 0; i < a.length; i++) {
        dot += a[i] * b[i];
        normA += a[i] * a[i];
        normB += b[i] * b[i];
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
}
```

### 1-4. IngestionPipeline 변경

```
기존: parse → ChunkingStrategy.chunk() → embed → save
변경: parse → SemanticChunkingStrategy.chunk() → embed → save
```

- `ChunkingStrategy`를 인터페이스로 추출
- `FixedSizeChunkingStrategy` (기존)과 `SemanticChunkingStrategy` (신규) 구현
- `app.chunking.mode` 설정으로 선택

### 1-5. 주의사항

- 문장이 5개 미만인 짧은 문서: 시맨틱 분할이 통계적으로 불안정 → 전체를 하나의 청크로 처리
- 임베딩 비용: 문장별 임베딩이 필요하지만 로컬 Ollama라 비용 0. 배치 처리로 속도 확보
- 임베딩은 **청크 분할 결정**에만 사용. 최종 저장되는 임베딩은 분할된 청크 단위로 다시 생성

---

## 2. 계층적 청킹 (Hierarchical Chunking)

시맨틱 청킹의 결과를 parent로, 그 안에서 고정 크기 분할한 것을 child로 사용한다.

### 2-1. 구조

```
시맨틱 청크 (Parent, 500~1500자) ← LLM 컨텍스트용
  ├─ Child 0 (200~500자) ← 검색 대상 (임베딩 저장)
  ├─ Child 1 (200~500자) ← 검색 대상
  └─ Child 2 (200~500자) ← 검색 대상
```

- **Parent**: 시맨틱 청킹으로 생성. 임베딩 없음. 의미 단위의 큰 블록
- **Child**: parent를 고정 크기(500자, 오버랩 100자)로 재분할. 임베딩 + tsvector 저장
- **검색 흐름**: child 임베딩으로 매칭 → parent content를 LLM에 전달

### 2-2. DB 변경

`V5__add_parent_chunk.sql`:

```sql
ALTER TABLE document_chunk ADD COLUMN parent_chunk_id UUID REFERENCES document_chunk(id);
CREATE INDEX idx_chunk_parent ON document_chunk(parent_chunk_id);
```

- `parent_chunk_id IS NULL` + `embedding IS NULL` → parent 청크
- `parent_chunk_id IS NOT NULL` + `embedding IS NOT NULL` → child 청크

### 2-3. Entity 변경

`DocumentChunk`에 `parentChunkId` 필드 추가:

```java
@Column(name = "parent_chunk_id")
private UUID parentChunkId;
```

### 2-4. IngestionPipeline 변경

```
1. parse → 시맨틱 청킹 → parent 청크 리스트
2. 각 parent → 고정 크기 분할 → child 청크 리스트
3. parent 저장 (content만, 임베딩 없음)
4. child 저장 (content + 임베딩 + tsvector, parent_chunk_id 참조)
```

### 2-5. SearchService 변경

검색은 child에서 수행하되, LLM 컨텍스트에는 parent를 전달:

```sql
-- 기존: child content를 직접 사용
-- 변경: child 매칭 → parent content 조회
SELECT p.content, p.chunk_index, d.filename
FROM document_chunk c
JOIN document_chunk p ON p.id = c.parent_chunk_id
JOIN document d ON d.id = p.document_id
WHERE c.id = :childChunkId
```

parent가 없는 청크(Phase 1~3에서 생성된 기존 청크)는 자기 자신의 content를 사용하도록 fallback.

---

## 3. Agentic RAG

### 3-1. 적응적 검색 (`SearchAgent`)

**현재**: `QueryRouter`가 RAG/GENERAL 2분류
**변경**: 3분류 + 검색 결과 품질 판단 루프

```java
enum AgentAction {
    SEARCH,           // 문서 검색 필요
    DIRECT_ANSWER,    // LLM 지식으로 직접 답변
    CLARIFY           // 질문이 모호 → 사용자에게 되묻기
}
```

**위치**: `com.example.rag.agent/SearchAgent`

**Agent 루프** (최대 3회 반복):

```
1. LLM에게 질문 분석 → AgentAction 결정
2. SEARCH → 검색 실행 → 결과 충분? → 답변 / 부족 → 쿼리 수정 후 재검색
3. DIRECT_ANSWER → 바로 답변
4. CLARIFY → 되묻기 응답 생성
```

**프롬프트** (`prompts/agent-decide.txt`):

```
아래 질문을 분석하고, 적절한 행동을 선택하세요.
"SEARCH", "DIRECT_ANSWER", "CLARIFY" 중 하나만 답하세요.

- SEARCH: 업로드된 문서에서 정보를 찾아야 할 때
- DIRECT_ANSWER: 일반 지식으로 답변 가능할 때
- CLARIFY: 질문이 너무 모호하여 구체화가 필요할 때

질문: {query}
```

**ChatService 변경**: `QueryRouter` 대신 `SearchAgent`로 교체

### 3-2. 멀티스텝 추론 (`MultiStepReasoner`)

**목적**: 복합 질문을 하위 질문으로 분해하여 각각 검색 후 종합

**예시**:

```
"Spring Boot의 버전과 React의 버전을 비교해줘"
  → 하위 질문 1: "Spring Boot 버전이 뭐야?" → 검색 → "3.4.4"
  → 하위 질문 2: "React 버전이 뭐야?" → 검색 → "19"
  → 종합: 두 결과를 합쳐서 비교 답변 생성
```

**위치**: `com.example.rag.agent/MultiStepReasoner`

**프롬프트** (`prompts/decompose.txt`):

```
아래 질문이 단일 검색으로 답할 수 있으면 "SINGLE"이라고 답하세요.
여러 단계가 필요하면, 각 하위 질문을 한 줄에 하나씩 작성하세요.

질문: {query}
```

### 3-3. 도구 사용 (Tool Use)

Spring AI Function Calling으로 LLM이 도구를 자율 선택:
- `vectorSearch(query)`, `keywordSearch(query)`, `getDocumentList()`, `getChunkContent(chunkId)`
- 모델 지원 여부에 의존 (gpt-oss:20b 미지원 시, Anthropic Claude 전환 시 활성화)

---

## 4. HyDE — 스킵

> 보류. 실질적 효과 불명확.

---

## 5. 구현 순서

### Step 1: 시맨틱 청킹

기존 고정 크기 청킹을 시맨틱 청킹으로 교체.

1. `ChunkingStrategy` 인터페이스 추출
2. `FixedSizeChunkingStrategy` (기존 코드 이동)
3. `SemanticChunkingStrategy` 구현 (문장 분리 → buffer 임베딩 → breakpoint 탐지)
4. `application.yml`에 `app.chunking.mode` 설정 추가
5. IngestionPipeline에서 mode에 따라 전략 선택
6. **검증**: 동일 문서를 fixed/semantic 두 방식으로 청킹 → 청크 경계 비교

### Step 2: 계층적 청킹

시맨틱 청크를 parent, 고정 크기 재분할을 child로 구성.

1. DB 마이그레이션 (`document_chunk.parent_chunk_id`)
2. `DocumentChunk` entity에 `parentChunkId` 추가
3. IngestionPipeline: parent 저장 → child 분할/임베딩/저장
4. SearchService: child 매칭 → parent content 조회 → LLM에 전달
5. **검증**: child로 검색 → parent 컨텍스트가 LLM에 전달되는지 확인

### Step 3: Agentic RAG

파이프라인 구조 변경.

1. SearchAgent 구현 (QueryRouter 대체)
2. MultiStepReasoner 구현
3. ChatService 에이전트 루프 적용
4. Tool Use (모델 지원 시)
5. **검증**: 복합 질문 분해 + 단계별 검색 동작 확인

---

## 6. 핵심 의사결정 요약

| 결정 | 선택 | 이유 |
|------|------|------|
| 청킹 전략 | 시맨틱 (고정 크기 대체) | 의미 단위 분할로 검색 정확도 향상, LLM 호출 불필요 (임베딩만 사용) |
| Breakpoint 방식 | Percentile (90th) | 문서별 적응적 임계치, 범용적 |
| Buffer size | 1 (전후 1문장) | 노이즈 감소 + 경계 유지 균형점 |
| 최소/최대 청크 | 200자 / 1500자 | 너무 작으면 맥락 부족, 너무 크면 검색 정밀도 하락 |
| 계층 구조 | parent-child 2레벨 | 시맨틱 = parent, 고정 분할 = child. 검색 정밀도 + 컨텍스트 풍부함 |
| Contextual 보강 | 스킵 (문서 요약 프리픽스) | 전체 문서 요약은 비용 대비 효과 불명확. 시맨틱 청킹 + 계층적 구조로 대체 |
| HyDE | 스킵 | 실질적 효과 불명확 |
| Agent 루프 | 최대 3회 반복 | 무한 루프 방지, 비용 제어 |
| 구현 순서 | 시맨틱 → 계층적 → Agent | 인덱싱 품질 먼저 개선, 파이프라인 구조 변경은 마지막 |
