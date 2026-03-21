# Phase 12 — RAG 성능 자동 평가 (Automated RAG Evaluation)

문서 내용에서 질문-답변 테스트 세트를 자동 생성하고, RAG 파이프라인에 돌려서 성능을 자동 측정한다.

---

## 1. 전체 흐름

```
[관리자] 문서 선택 → "테스트 생성" 클릭
    ↓
[LLM] 문서 청크 읽고 질문-기대답변 쌍 자동 생성
    ↓
[DB] 테스트 세트 저장
    ↓
[관리자] "평가 실행" 클릭
    ↓
[RAG] 각 질문을 RAG 파이프라인에 전송 → 실제 답변 수집
    ↓
[LLM-as-Judge] faithfulness, relevance, correctness 자동 채점
    ↓
[대시보드] 점수 시각화 + 이전 실행과 비교
```

---

## 2. 테스트 세트 생성

### 2-1. 질문 유형 (다양성 확보)
- **사실 질문**: 문서에 명시된 사실 확인 ("X는 무엇인가?")
- **추론 질문**: 여러 정보를 종합해야 하는 질문 ("A와 B의 차이는?")
- **요약 질문**: 문서 내용 요약 요청 ("이 문서의 핵심 내용은?")

### 2-2. 생성 프롬프트
LLM에 문서 청크를 주고 질문-기대답변-질문유형 세트를 생성:

```
다음 문서 내용을 읽고, 이 내용으로 답변할 수 있는 질문과 기대 답변을 생성하세요.
다양한 유형의 질문을 만들어주세요: 사실 확인, 추론, 요약

문서 내용:
{chunk_content}

JSON 배열로 출력:
[
  { "question": "...", "expectedAnswer": "...", "type": "FACTUAL|REASONING|SUMMARY" }
]
```

### 2-3. 청크당 2~3개 질문 생성 → 문서당 10~30개 질문

---

## 3. 평가 실행

### 3-1. RAG 응답 수집
- 각 테스트 질문을 ChatService.chat()에 전달 (내부 호출, WebSocket 불필요)
- 실제 RAG 파이프라인 전체 경로 통과 (검색 → 리랭킹 → 생성)
- 응답 텍스트 + 검색된 소스 저장

### 3-2. LLM-as-Judge 평가
각 응답에 대해 LLM이 자동 채점:

| 메트릭 | 측정 | 입력 |
|--------|------|------|
| **Faithfulness** | 응답이 검색된 컨텍스트에 기반하는지 | context + response |
| **Relevance** | 응답이 질문에 적절한지 | question + response |
| **Correctness** | 응답이 기대 답변과 일치하는지 | expectedAnswer + response |

채점 프롬프트:
```
다음 질문에 대한 AI 응답을 평가하세요.
질문: {question}
기대 답변: {expected_answer}
실제 응답: {actual_response}
검색된 컨텍스트: {context}

각 항목을 1~5점으로 채점하고 JSON으로 출력:
{ "faithfulness": N, "relevance": N, "correctness": N, "comment": "..." }
```

---

## 4. DB 스키마

```sql
-- 평가 실행 (벤치마크 단위)
CREATE TABLE eval_run (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_questions INT NOT NULL DEFAULT 0,
    completed_questions INT NOT NULL DEFAULT 0,
    avg_faithfulness DOUBLE PRECISION,
    avg_relevance    DOUBLE PRECISION,
    avg_correctness  DOUBLE PRECISION,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    completed_at TIMESTAMP
);

-- 테스트 질문
CREATE TABLE eval_question (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    eval_run_id     UUID NOT NULL REFERENCES eval_run(id) ON DELETE CASCADE,
    document_id     UUID REFERENCES document(id),
    question        TEXT NOT NULL,
    expected_answer TEXT NOT NULL,
    question_type   VARCHAR(20) NOT NULL,
    actual_response TEXT,
    retrieved_context TEXT,
    faithfulness    DOUBLE PRECISION,
    relevance       DOUBLE PRECISION,
    correctness     DOUBLE PRECISION,
    judge_comment   TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
```

---

## 5. API

```
# 테스트 세트 생성
POST /api/admin/eval/generate
  Body: { documentIds: [...], questionsPerChunk: 3 }
  → 질문 생성 시작 (비동기), eval_run 생성

# 평가 실행
POST /api/admin/eval/runs/{id}/execute
  → RAG 파이프라인으로 질문 전송 + 채점 시작 (비동기)

# 실행 목록/상세
GET  /api/admin/eval/runs
GET  /api/admin/eval/runs/{id}
GET  /api/admin/eval/runs/{id}/questions

# 삭제
DELETE /api/admin/eval/runs/{id}
```

---

## 6. Frontend

### 6-1. 관리 사이드바
- "평가" 항목 추가 → `/admin/eval`

### 6-2. 평가 페이지
- **실행 목록**: 이름, 상태, 질문 수, 평균 점수, 실행일
- **새 평가 생성**: 문서 선택 → 질문 수 설정 → "생성" 버튼
- **실행 상세**: 질문별 점수 테이블 + 평균 점수 카드 + 점수 분포 차트
- **비교 뷰**: 두 실행을 선택해서 점수 비교 (선택)

---

## 7. 구현 순서

```
Step 1: DB 마이그레이션 + 엔티티 + Repository
Step 2: 질문 자동 생성 서비스 (LLM 프롬프트)
Step 3: 평가 실행 서비스 (RAG 호출 + LLM-as-Judge)
Step 4: API 엔드포인트
Step 5: Frontend 평가 페이지
Step 6: 빌드 및 검증
```

---

## 8. 주의사항

- 질문 생성과 평가 실행은 비동기 (많은 LLM 호출 → 시간 소요)
- 진행률을 DB에 저장하여 UI에서 폴링으로 상태 표시
- LLM-as-Judge는 QUERY 모델 사용 (비용 절약)
- 기존 EvaluationService의 샘플링 평가와 별도로, 이 기능은 관리자가 직접 트리거하는 벤치마크
