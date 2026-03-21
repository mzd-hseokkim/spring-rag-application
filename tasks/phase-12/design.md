# Phase 12 — RAG 성능 자동 평가 설계 (Design Document)

---

## 1. 현재 상태

### 기존 평가
- `EvaluationService.evaluateIfSampled()`: 10% 샘플링으로 faithfulness/relevance 평가
- 비동기 실행, 결과는 로그에만 출력
- `evaluation_result` 테이블 존재하지만 데이터 미저장

### 사용 가능한 인프라
- `DocumentChunkRepository.findByDocumentIdOrderByChunkIndex()`: 문서별 청크 조회
- `ModelClientProvider.getChatClient(ModelPurpose.QUERY)`: 평가용 LLM 호출
- `ChatService.chat()`: RAG 파이프라인 내부 호출 가능
- `PromptLoader`: 프롬프트 파일 로딩

---

## 2. DB 스키마

### V15

```sql
CREATE TABLE eval_run (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(200) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_questions     INT NOT NULL DEFAULT 0,
    completed_questions INT NOT NULL DEFAULT 0,
    avg_faithfulness    DOUBLE PRECISION,
    avg_relevance       DOUBLE PRECISION,
    avg_correctness     DOUBLE PRECISION,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    completed_at        TIMESTAMP
);

CREATE TABLE eval_question (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    eval_run_id       UUID NOT NULL REFERENCES eval_run(id) ON DELETE CASCADE,
    document_id       UUID REFERENCES document(id),
    question          TEXT NOT NULL,
    expected_answer   TEXT NOT NULL,
    question_type     VARCHAR(20) NOT NULL,
    actual_response   TEXT,
    retrieved_context TEXT,
    faithfulness      DOUBLE PRECISION,
    relevance         DOUBLE PRECISION,
    correctness       DOUBLE PRECISION,
    judge_comment     TEXT,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_eval_question_run ON eval_question (eval_run_id);
```

---

## 3. Backend 설계

### 3-1. 패키지 구조

```
com.example.rag.evaluation/
├── EvaluationService.java           # 기존 (샘플링 평가)
├── EvalRunEntity.java               # 평가 실행 엔티티
├── EvalQuestionEntity.java          # 테스트 질문 엔티티
├── EvalRunRepository.java
├── EvalQuestionRepository.java
├── AutoEvalService.java             # 핵심: 질문 생성 + 평가 실행
└── AutoEvalController.java          # API 엔드포인트
```

### 3-2. AutoEvalService — 질문 생성

```java
@Async("ingestionExecutor")
public void generateQuestions(UUID runId, List<UUID> documentIds, int questionsPerChunk) {
    EvalRun run = updateStatus(runId, "GENERATING");

    for (UUID docId : documentIds) {
        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
        // 부모 청크만 사용 (parentChunkId == null → 상위 레벨 청크)
        List<DocumentChunk> parentChunks = chunks.stream()
                .filter(c -> c.getParentChunkId() == null)
                .toList();

        for (DocumentChunk chunk : parentChunks) {
            String prompt = questionGenerationPrompt.formatted(chunk.getContent());
            String response = queryClient.prompt().user(prompt).call().content();
            // JSON 파싱 → EvalQuestion 엔티티로 저장
            List<QA> qas = parseJsonArray(response);
            for (QA qa : qas) {
                save(new EvalQuestion(runId, docId, qa.question, qa.expectedAnswer, qa.type));
            }
        }
    }

    run.setTotalQuestions(countQuestions(runId));
    updateStatus(runId, "READY");
}
```

### 3-3. 질문 생성 프롬프트

```
prompts/eval-question-gen.txt:

다음 문서 내용을 읽고, 이 내용으로 답변할 수 있는 질문과 기대 답변을 생성하세요.
다양한 유형의 질문을 %d개 만들어주세요.

유형:
- FACTUAL: 문서에 명시된 사실 확인
- REASONING: 여러 정보를 종합한 추론
- SUMMARY: 내용 요약 요청

문서 내용:
%s

반드시 다음 JSON 배열 형식으로만 출력하세요:
[
  { "question": "질문 내용", "expectedAnswer": "기대 답변", "type": "FACTUAL" }
]
```

### 3-4. AutoEvalService — 평가 실행

```java
@Async("ingestionExecutor")
public void executeRun(UUID runId) {
    EvalRun run = updateStatus(runId, "RUNNING");
    List<EvalQuestion> questions = questionRepository.findByEvalRunId(runId);

    for (EvalQuestion q : questions) {
        // 1. RAG 파이프라인 호출 (non-streaming)
        RagResult result = callRagPipeline(q.getQuestion());
        q.setActualResponse(result.response());
        q.setRetrievedContext(result.context());

        // 2. LLM-as-Judge 채점
        String judgePrompt = judgingPrompt.formatted(
                q.getQuestion(), q.getExpectedAnswer(),
                result.response(), result.context());
        String judgeResponse = queryClient.prompt().user(judgePrompt).call().content();

        // 3. 점수 파싱
        JudgeResult scores = parseJudgeResult(judgeResponse);
        q.setFaithfulness(scores.faithfulness());
        q.setRelevance(scores.relevance());
        q.setCorrectness(scores.correctness());
        q.setJudgeComment(scores.comment());
        q.setStatus("COMPLETED");

        questionRepository.save(q);
        run.setCompletedQuestions(run.getCompletedQuestions() + 1);
        runRepository.save(run);
    }

    // 4. 평균 점수 계산
    updateAverages(run);
    updateStatus(runId, "COMPLETED");
}
```

### 3-5. RAG 내부 호출

ChatService의 streaming 대신 **non-streaming** 방식으로 RAG를 호출합니다:

```java
private RagResult callRagPipeline(String question) {
    // 검색
    String searchQuery = queryCompressor.compress("eval-session", question);
    List<ChunkSearchResult> results = searchService.search(searchQuery, List.of());

    String context = results.stream()
            .map(r -> "[%s] %s".formatted(r.filename(), r.contextContent()))
            .collect(Collectors.joining("\n\n"));

    // 생성 (non-streaming)
    String response = chatClient.prompt()
            .system(ragSystemPrompt)
            .user(context + "\n\n질문: " + question)
            .call()
            .content();

    return new RagResult(response, context);
}
```

### 3-6. 채점 프롬프트

```
prompts/eval-judge.txt:

당신은 RAG 시스템의 응답 품질을 평가하는 심사관입니다.
다음 질문에 대한 AI 응답을 평가하세요.

질문: %s
기대 답변: %s
실제 응답: %s
검색된 컨텍스트: %s

다음 항목을 각각 1~5점으로 채점하세요:
- faithfulness: 응답이 검색된 컨텍스트에 기반한 사실인지 (1=완전 환각, 5=완벽 근거)
- relevance: 응답이 질문에 적절히 답하는지 (1=무관, 5=완벽)
- correctness: 응답이 기대 답변과 의미적으로 일치하는지 (1=완전 불일치, 5=완벽 일치)

반드시 다음 JSON 형식으로만 출력하세요:
{ "faithfulness": N, "relevance": N, "correctness": N, "comment": "간단한 평가 코멘트" }
```

---

## 4. API

```
POST /api/admin/eval/generate
  Body: { name: "평가명", documentIds: [...], questionsPerChunk: 3 }
  → 202 Accepted, { id: "run-uuid", status: "GENERATING" }

POST /api/admin/eval/runs/{id}/execute
  → 202 Accepted, { status: "RUNNING" }

GET  /api/admin/eval/runs
  → 실행 목록 (최신순)

GET  /api/admin/eval/runs/{id}
  → 실행 상세 (평균 점수, 진행률)

GET  /api/admin/eval/runs/{id}/questions
  → 질문별 결과 목록

DELETE /api/admin/eval/runs/{id}
```

---

## 5. Frontend 설계

### 5-1. AdminSidebar 추가
```
▸ 대시보드
▸ 평가          ← 신규
▸ 사용자 관리
▸ 문서 관리
...
```

### 5-2. 평가 페이지 구성

```
┌───────────────────────────────────────────────────────┐
│ RAG 성능 평가                          [+ 새 평가 생성] │
├───────────────────────────────────────────────────────┤
│ 실행 목록                                              │
│ 이름        │ 상태     │ 질문 수 │ F  │ R  │ C  │ 일시  │
│ 평가 #1    │ COMPLETED│ 24     │4.2 │4.5 │3.8 │ 03-22│
│ 평가 #2    │ RUNNING  │ 18     │ -  │ -  │ -  │ 03-22│
└───────────────────────────────────────────────────────┘

클릭 시 상세:
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ Faithfulness │ │ Relevance    │ │ Correctness  │
│    4.2 / 5   │ │   4.5 / 5    │ │   3.8 / 5    │
└──────────────┘ └──────────────┘ └──────────────┘

┌───────────────────────────────────────────────────────┐
│ 질문별 결과                                            │
│ 질문          │ 유형    │ F  │ R  │ C  │ 코멘트        │
│ X는 무엇인가? │ FACTUAL │ 5  │ 5  │ 4  │ 정확하나 ...  │
│ A와 B 차이?   │ REASON  │ 3  │ 4  │ 3  │ 일부 누락 ... │
└───────────────────────────────────────────────────────┘
```

### 5-3. 새 평가 생성 다이얼로그
- 평가 이름 입력
- 문서 체크박스 (COMPLETED 문서만)
- 청크당 질문 수 (기본 3)
- "생성" 버튼 → API 호출 → 목록에 GENERATING 상태로 표시

---

## 6. 구현 순서

```
Step 1: DB + 엔티티 + Repository
Step 2: 프롬프트 파일 + AutoEvalService (생성 + 실행)
Step 3: AutoEvalController
Step 4: Frontend 평가 페이지
Step 5: 빌드 및 검증
```

---

## 7. 주의사항

- 질문 생성 시 부모 청크(parentChunkId == null)만 사용 → 자식 청크는 내용이 짧아 의미 있는 질문 생성 어려움
- LLM 호출이 많으므로 (질문 생성 N + RAG N + 채점 N) 비용/시간 고려
- 진행률 폴링: 프론트엔드에서 5초 간격으로 상태 조회
- RAG 호출 시 userId 없이 전체 문서 대상 검색 (평가 목적)
