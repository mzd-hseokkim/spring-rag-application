# Phase 5 — LLM 모델 관리 (Multi-Model Registry)

LLM 모델을 DB에 등록하고, 용도별로 선택하여 사용할 수 있도록 한다.
초기 지원 대상: Ollama (로컬), Anthropic Claude (API).

---

## 1. 모델 레지스트리 (Model Registry)

### 1-1. 모델 등록/관리
- DB 테이블에 사용 가능한 LLM 모델 목록 관리
- 모델별 정보: 제공자(provider), 모델명, 용도, 활성 상태
- 관리 API로 모델 추가/수정/삭제/활성화/비활성화

### 1-2. 모델 메타데이터
- 제공자 (provider): `OLLAMA`, `ANTHROPIC`
- 모델 ID: `gpt-oss:20b`, `claude-sonnet-4-20250514` 등
- 용도 (purpose): `CHAT`, `EMBEDDING`, `RERANK`, `EVALUATION`
- 연결 정보: base URL (Ollama), API key 참조 (Anthropic)
- 파라미터: temperature, max tokens 등

### 1-3. 기본 모델 지정
- 용도별로 기본(default) 모델 하나 지정
- 기본 모델이 없으면 해당 기능 비활성화

---

## 2. 용도별 모델 분리

### 2-1. 용도 정의

| 용도 | 설명 | 현재 사용 위치 |
|------|------|---------------|
| CHAT | 사용자 대화 응답 생성 | ChatService |
| EMBEDDING | 문서/질의 임베딩 | IngestionPipeline, VectorSearchService |
| RERANK | 검색 결과 리랭킹 | RerankService |
| EVALUATION | 응답 품질 평가 | FaithfulnessEvaluator, RelevanceEvaluator |
| QUERY | 쿼리 변환 (압축, 확장, 라우팅, Agent) | QueryCompressor, QueryExpander, SearchAgent 등 |

### 2-2. 모델 선택 전략
- 용도별로 다른 모델 사용 가능 (예: CHAT은 Claude, QUERY는 Ollama)
- 비용 최적화: 단순 작업(QUERY)은 가벼운 모델, 핵심 작업(CHAT)은 고성능 모델
- Fallback: 지정 모델 호출 실패 시 대체 모델로 자동 전환

---

## 3. 제공자별 어댑터

### 3-1. Ollama 어댑터
- 로컬 Ollama 서버의 모델 목록 자동 조회 (`/api/tags`)
- Chat 모델: `ChatClient` 동적 생성
- Embedding 모델: `EmbeddingModel` 동적 생성

### 3-2. Anthropic 어댑터
- API 키 기반 인증
- Chat 모델만 지원 (Anthropic은 임베딩 미제공)
- 모델 목록: 수동 등록 (API로 모델 목록 조회 불가)

---

## 4. 관리 API

### 4-1. 모델 CRUD

```
GET    /api/models                    # 모델 목록 조회
POST   /api/models                    # 모델 등록
PUT    /api/models/{id}               # 모델 수정
DELETE /api/models/{id}               # 모델 삭제
PATCH  /api/models/{id}/activate      # 모델 활성화
PATCH  /api/models/{id}/deactivate    # 모델 비활성화
PATCH  /api/models/{id}/set-default   # 기본 모델 지정
```

### 4-2. 모델 테스트

```
POST /api/models/{id}/test            # 모델 연결 테스트 (ping)
```

- Ollama: `/api/tags` 호출하여 모델 존재 확인
- Anthropic: 짧은 메시지로 API 호출 테스트

### 4-3. Ollama 자동 검색

```
GET /api/models/discover/ollama       # Ollama에 설치된 모델 목록 조회
```

---

## 5. 관리 UI (프론트엔드)

### 5-1. 모델 관리 화면
- 등록된 모델 목록 표시 (제공자, 모델명, 용도, 상태)
- 모델 추가/수정/삭제
- 용도별 기본 모델 선택 드롭다운
- 연결 테스트 버튼

### 5-2. 사이드바 확장
- 기존: 문서 관리
- 추가: 모델 관리 탭 또는 설정 페이지

---

## 6. 채팅 시 모델 선택

### 6-1. 기본 동작
- 별도 지정 없으면 용도별 기본 모델 사용

### 6-2. 채팅별 모델 지정 (선택)
- ChatRequest에 `modelId` 필드 추가 (옵션)
- 프론트엔드에서 모델 선택 드롭다운 제공

---

## 처리 방식 요약

| 기능 | 동기/비동기 | 비고 |
|------|------------|------|
| 모델 CRUD | 동기 | 관리 API |
| 모델 연결 테스트 | 동기 | 타임아웃 5초 |
| Ollama 자동 검색 | 동기 | Ollama API 호출 |
| 모델 동적 전환 | 동기 | 요청 시 모델 선택 |
| Fallback 전환 | 동기 | 실패 감지 시 즉시 |
