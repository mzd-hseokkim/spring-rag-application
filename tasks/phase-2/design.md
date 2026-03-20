# Phase 2 — Retrieval Enhancement + Conversation: 상세 설계

## 현재 상태 (Phase 1 완료)

- 검색: `SearchService` → 벡터/키워드 병렬 검색 → RRF 퓨전 → top-5 반환
- 채팅: `ChatService.chat(String message)` → 단일 턴, 세션 없음
- 인프라: Redis 컨테이너 준비됨 (docker-compose, 포트 6381), 아직 미사용

---

## 전체 파이프라인 흐름 (Phase 2 적용 후)

```
사용자 메시지 + sessionId
    ↓
[1] 시맨틱 캐시 조회 → 히트 시 즉시 반환
    ↓ (미스)
[2] 대화 이력 조회 (Redis)
    ↓
[3] 대화 압축 → 독립 질의 생성
    ↓
[4] 쿼리 라우팅 → RAG / 일반 대화 분기
    ↓ (RAG)
[5] Multi-Query Expansion → 변형 질의 N개 생성
    ↓
[6] 하이브리드 검색 (기존) × N개 질의
    ↓
[7] RRF 퓨전 (기존)
    ↓
[8] 리랭킹 → 최종 top-K
    ↓
[9] LLM 응답 생성 (기존)
    ↓
[10] 대화 이력 저장 + 시맨틱 캐시 저장 (비동기)
```

---

## 1. 멀티턴 대화 관리

### 1-1. 패키지 구조

```
com.example.rag.conversation/
├── ConversationService       # 대화 이력 CRUD
├── ConversationMessage       # 메시지 record (role, content, timestamp)
└── ConversationConfig        # Redis 설정
```

### 1-2. Redis 데이터 구조

- **Key**: `conversation:{sessionId}`
- **Type**: Redis List (RPUSH/LRANGE)
- **Value**: JSON 직렬화된 `ConversationMessage`
- **TTL**: 1시간 (설정 가능)

### 1-3. 세션 관리

- 프론트엔드에서 `sessionId`를 생성 (UUID)하여 요청에 포함
- `ChatRequest`에 `sessionId` 필드 추가
- 세션이 없으면 새 세션 자동 시작

### 1-4. 컨텍스트 윈도우

- 최근 N개 메시지만 유지 (기본값: 최근 20개)
- 메시지 수 초과 시 오래된 것부터 자동 삭제 (LTRIM)
- 토큰 기반 압축은 대화 압축(쿼리 변환)에서 처리

### 1-5. 의존성 추가

- `spring-boot-starter-data-redis` (build.gradle.kts)

### 1-6. API 변경

```
POST /api/chat
{
  "message": "질문",
  "sessionId": "uuid"       ← 추가
}

GET /api/chat/sessions/{sessionId}/messages   ← 신규 (이력 조회)
DELETE /api/chat/sessions/{sessionId}         ← 신규 (세션 삭제)
```

### 1-7. 프론트엔드 변경

- `useChat` 훅에서 `sessionId` 상태 관리
- "새 대화" 버튼으로 새 세션 시작
- 이전 메시지를 화면에 표시 (세션 내)

---

## 2. 쿼리 변환

### 2-1. 대화 압축 (`QueryCompressor`)

**목적**: 멀티턴 대화를 독립적인 단일 질의로 변환

**위치**: `com.example.rag.search.query/QueryCompressor`

**동작**:
1. ConversationService에서 최근 대화 이력 조회
2. 이력 + 현재 질문을 LLM에 전달
3. 독립적인 검색 질의 반환

**프롬프트 설계**:
```
아래 대화 이력과 후속 질문이 주어졌을 때, 대화 맥락을 반영하여
독립적으로 이해 가능한 검색 질의를 한 문장으로 작성하세요.

[대화 이력]
{history}

[후속 질문]
{question}

[독립 질의]
```

**스킵 조건**: 대화 이력이 없으면 (첫 질문) 압축 생략, 원본 질의 사용

### 2-2. Multi-Query Expansion (`QueryExpander`)

**목적**: 하나의 질의를 여러 관점의 변형 질의로 확장하여 검색 재현율 향상

**위치**: `com.example.rag.search.query/QueryExpander`

**동작**:
1. 압축된 질의를 LLM에 전달
2. 의미적으로 다른 3개의 변형 질의 생성
3. 원본 + 변형 = 총 4개 질의로 검색

**프롬프트 설계**:
```
아래 질문에 대해 같은 의미이지만 다른 표현의 검색 질의를 3개 생성하세요.
각 줄에 하나씩 작성하세요.

질문: {query}
```

**SearchService 변경**: 4개 질의를 각각 검색 → 결과를 모두 RRF 퓨전에 전달

---

## 3. 리랭킹

### 3-1. 리랭커 구현 (`RerankService`)

**위치**: `com.example.rag.search/RerankService`

**접근 방식**: 로컬 Ollama 모델 활용 LLM 기반 리랭킹
- 별도 Cross-Encoder 서버 없이, LLM에게 질의-문서 관련성 점수를 매기게 함
- MVP에서는 단순하지만 효과적인 접근

**동작**:
1. RRF 퓨전 결과 (상위 10~15건)를 받음
2. 각 청크에 대해 LLM으로 관련성 평가 (0~10 점수)
3. 점수 기준 재정렬, 상위 K건 반환

**프롬프트 설계**:
```
질문과 문서 조각의 관련성을 0~10 점수로 평가하세요. 숫자만 답하세요.

질문: {query}
문서: {chunk_content}

점수:
```

**대안 (Phase 3에서 고려)**: Ollama에 Cross-Encoder 전용 모델 배포 (bge-reranker 등)

### 3-2. SearchService 파이프라인 변경

```
기존: 검색 → RRF 퓨전 → 반환
변경: 검색 → RRF 퓨전 → 리랭킹 → 반환
```

---

## 4. 쿼리 라우팅

### 4-1. 라우터 구현 (`QueryRouter`)

**위치**: `com.example.rag.search.query/QueryRouter`

**라우팅 타입**:
```java
enum QueryRoute {
    RAG,          // 문서 기반 질의 → 검색 파이프라인
    GENERAL       // 일반 대화/인사 → LLM 직접 응답
}
```

**분류 방식**: LLM 기반 분류
```
아래 질문이 문서 검색이 필요한 질문인지, 일반 대화인지 분류하세요.
"RAG" 또는 "GENERAL" 중 하나만 답하세요.

질문: {query}
```

### 4-2. ChatService 변경

```
기존: message → search → LLM 응답
변경: message → 라우팅 분류
         ├─ RAG → search → LLM 응답 (컨텍스트 포함)
         └─ GENERAL → LLM 직접 응답 (검색 생략)
```

---

## 5. 시맨틱 캐싱 — 스킵 (보류)

> **결정**: Phase 2에서는 구현하지 않는다.
>
> LLM 응답을 캐싱하는 것은 RAG 특성상 문제가 있다:
> - 문서가 추가/변경되면 같은 질문이어도 답변이 달라져야 함
> - 대화 맥락에 따라 답변이 달라지는데 캐시는 이를 무시함
> - 캐시 무효화 타이밍이 애매함 (부분 무효화가 어려움)
>
> 필요 시 Phase 3 이후에 재검토한다.

### 5-1. RAG 애플리케이션의 일반적인 캐싱 전략

RAG 파이프라인에서 캐싱은 **어떤 계층을 캐싱하느냐**에 따라 트레이드오프가 달라진다.

#### (1) 임베딩 캐싱 (가장 안전)

- **대상**: 질의 텍스트 → 임베딩 벡터
- **장점**: 동일 질의의 임베딩 API 호출 절약, 부작용 없음
- **단점**: 절약 효과가 작음 (로컬 Ollama는 이미 빠름)
- **무효화**: 임베딩 모델이 바뀔 때만
- **적합**: 외부 임베딩 API 사용 시 (OpenAI 등 유료 API 비용 절감)

#### (2) 검색 결과 캐싱 (중간 수준)

- **대상**: 질의 → 검색된 청크 목록 (임베딩 검색 + 키워드 검색 + 퓨전 결과)
- **장점**: 검색 파이프라인 전체를 건너뜀, LLM은 항상 호출하므로 답변 품질 유지
- **단점**: 문서 변경 시 무효화 필요
- **무효화**: 문서 추가/삭제/수정 시 전체 또는 관련 캐시 플러시
- **TTL**: 짧게 유지 (5~15분)
- **적합**: 검색 비용이 높거나, 동일 질의 반복이 잦은 환경

#### (3) LLM 응답 캐싱 (가장 공격적)

- **대상**: 질의 → 최종 LLM 응답 전체
- **장점**: LLM 호출 비용/시간 완전 절약
- **단점**: 문서 변경, 대화 맥락, 프롬프트 변경 등에 민감. 오래된 답변 반환 위험
- **무효화**: 복잡함 — 문서 변경, 프롬프트 변경, 모델 변경 모두 고려해야 함
- **TTL**: 매우 짧게 (1~5분) 또는 비활성화
- **적합**: FAQ 성격의 반복 질의가 많고, 문서 변경이 드문 환경

#### (4) 하이브리드 캐싱 (실무 권장)

실무에서는 계층별로 다른 전략을 조합한다:

```
임베딩 캐싱 (항상 ON, 긴 TTL)
  + 검색 결과 캐싱 (문서 변경 시 무효화, 짧은 TTL)
  + LLM 응답 캐싱 (선택적, FAQ 패턴에만 적용)
```

#### (5) 시맨틱 유사도 기반 캐시 키

전통적인 exact-match 캐시 키 대신, **질의 임베딩 간 코사인 유사도**로 캐시 히트를 판단한다:
- "기술 스택이 뭐야?" ≈ "이 프로젝트의 tech stack은?" → 캐시 히트
- 유사도 임계치: 일반적으로 0.92~0.98 (높을수록 보수적)
- 구현 방식: Redis + 벡터 검색 (RediSearch) 또는 애플리케이션 레벨 전수 비교

---

## 6. 설정 추가 (`application.yml`)

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6381

app:
  conversation:
    max-messages: 20
    ttl-minutes: 60
  search:
    rerank-candidates: 15
    expansion-count: 3
  # cache: 스킵 (Phase 2에서 보류)
```

---

## 7. 구현 순서

각 단계는 독립적으로 검증 가능하도록 구성한다.

### Step 1: 멀티턴 대화 관리
1. Redis 의존성 + 설정
2. ConversationService 구현
3. ChatService/Controller에 sessionId 연동
4. 프론트엔드 세션 관리
5. **검증**: 같은 세션에서 이전 질문 참조 가능 확인

### Step 2: 쿼리 변환 (압축 + 확장)
1. QueryCompressor 구현
2. QueryExpander 구현
3. SearchService에 파이프라인 통합
4. **검증**: "그건 뭐야?" 같은 대명사 질문이 올바르게 검색되는지 확인

### Step 3: 쿼리 라우팅
1. QueryRouter 구현
2. ChatService에 라우팅 분기 적용
3. **검증**: "안녕하세요"에 검색 없이 답변, 문서 질문에는 RAG 답변

### Step 4: 리랭킹
1. RerankService 구현
2. SearchService에 리랭킹 단계 추가
3. **검증**: 검색 결과 순서가 질의 관련성에 따라 개선되는지 확인

### Step 5: 시맨틱 캐싱 — 스킵

보류 사유 및 일반적인 RAG 캐싱 전략은 섹션 5 참조.

---

## 8. 핵심 의사결정 요약

| 결정 | 선택 | 이유 |
|------|------|------|
| 대화 저장소 | Redis List | 이미 docker-compose에 준비됨, TTL 지원, 단순 구조 |
| 세션 식별 | 프론트엔드 생성 UUID | 서버 인증 없이 단순 구현, MVP에 적합 |
| 쿼리 변환 | LLM 기반 (자체 프롬프트) | Spring AI API에 의존하지 않고 제어 가능 |
| 리랭킹 | LLM 점수 평가 | 별도 모델 서버 불필요, Ollama 활용 |
| 시맨틱 캐싱 | 스킵 (보류) | LLM 응답 캐싱은 RAG 특성상 부작용이 큼. 필요 시 검색 결과 캐싱으로 재검토 |
