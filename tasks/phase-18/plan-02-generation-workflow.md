# Plan 02 — 생성 워크플로우

4단계 파이프라인(Plan → Generate → Review → Render)의 상세 설계.
Spring AI 구조화 출력을 활용한 콘텐츠 생성 로직을 정의한다.

---

## 1. 워크플로우 전체 흐름

```
GenerationWorkflowService.execute(job)
│
├─ 1) planOutline()        → DocumentOutline 생성
│     └─ RAG 검색 + AI 호출 → 아웃라인 JSON
│
├─ 2) generateSections()   → 섹션별 콘텐츠 생성
│     └─ for each section:
│         ├─ RAG 검색 (섹션 키워드 기반)
│         └─ AI 호출 → SectionContent JSON
│
├─ 3) reviewSections()     → 품질 검증 (선택적)
│     └─ AI 리뷰 → 미달 시 재생성
│
└─ 4) renderDocument()     → 최종 파일 생성
      └─ DocumentRendererService 호출
```

---

## 2. Spring AI 구조화 출력 (Structured Output)

### 2-1. 출력 Record 정의

```java
// Phase 1: 아웃라인
record DocumentOutline(
    String title,
    String summary,
    List<SectionPlan> sections
) {}

record SectionPlan(
    String key,           // "executive_summary", "problem_analysis" 등
    String heading,       // 섹션 제목
    String purpose,       // 이 섹션의 목적
    List<String> keyPoints,  // 반드시 포함할 내용
    int estimatedLength   // 예상 글자 수
) {}

// Phase 2: 섹션 콘텐츠
record SectionContent(
    String key,
    String title,
    String content,              // 본문 (마크다운 허용)
    List<String> highlights,     // 핵심 포인트
    List<ContentTable> tables,   // 표 데이터 (있을 경우)
    List<String> references      // 참고 출처
) {}

record ContentTable(
    String caption,
    List<String> headers,
    List<List<String>> rows
) {}

// Phase 3: 리뷰 결과
record ReviewResult(
    boolean approved,
    int qualityScore,           // 1-10
    List<String> issues,        // 발견된 문제
    List<String> suggestions    // 개선 제안
) {}
```

### 2-2. AI 호출 패턴

```java
@Service
public class ContentGeneratorService {
    private final ModelClientProvider modelClientProvider;

    // Phase 1: 아웃라인 생성
    public DocumentOutline generateOutline(String userInput, String templatePrompt,
                                           List<String> ragContext) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);

        return client.prompt()
            .system(templatePrompt)
            .user(u -> u.text("""
                다음 입력을 분석하여 문서 아웃라인을 생성하세요.

                [사용자 입력]
                {input}

                [참고 자료]
                {context}
                """)
                .param("input", userInput)
                .param("context", String.join("\n---\n", ragContext)))
            .call()
            .entity(DocumentOutline.class);
    }

    // Phase 2: 섹션별 콘텐츠 생성
    public SectionContent generateSection(SectionPlan plan, String templatePrompt,
                                          List<String> ragContext,
                                          List<SectionContent> previousSections) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);

        String previousContext = previousSections.stream()
            .map(s -> "## " + s.title() + "\n" + s.content())
            .collect(Collectors.joining("\n\n"));

        return client.prompt()
            .system(templatePrompt)
            .user(u -> u.text("""
                다음 섹션의 콘텐츠를 작성하세요.

                [섹션 정보]
                제목: {heading}
                목적: {purpose}
                포함할 내용: {keyPoints}

                [이전 섹션들 (문맥 참고)]
                {previous}

                [참고 자료]
                {context}
                """)
                .param("heading", plan.heading())
                .param("purpose", plan.purpose())
                .param("keyPoints", String.join(", ", plan.keyPoints()))
                .param("previous", previousContext)
                .param("context", String.join("\n---\n", ragContext)))
            .call()
            .entity(SectionContent.class);
    }
}
```

---

## 3. 워크플로우 서비스 구현

```java
@Service
public class GenerationWorkflowService {
    private final ContentGeneratorService contentGenerator;
    private final DocumentRendererService renderer;
    private final SearchService searchService;
    private final GenerationJobRepository jobRepository;
    private final SseEmitter emitter; // 진행률 전달

    @Async
    public void execute(GenerationJob job) {
        try {
            // Phase 1: PLAN
            updateStatus(job, PLANNING);
            emitProgress("문서 구조를 설계하고 있습니다...");

            List<String> ragContext = searchRelevantDocs(job);
            DocumentOutline outline = contentGenerator.generateOutline(
                job.getUserInput(),
                job.getTemplate().getSystemPrompt(),
                ragContext
            );
            job.setOutline(toJson(outline));
            job.setTotalSections(outline.sections().size());
            jobRepository.save(job);

            // Phase 2: GENERATE
            updateStatus(job, GENERATING);
            List<SectionContent> sections = new ArrayList<>();

            for (int i = 0; i < outline.sections().size(); i++) {
                SectionPlan plan = outline.sections().get(i);
                job.setCurrentSection(i + 1);
                emitProgress("섹션 생성 중: " + plan.heading());

                // 섹션별 RAG 검색 (섹션 키워드 기반)
                List<String> sectionContext = searchForSection(job, plan);

                SectionContent content = contentGenerator.generateSection(
                    plan,
                    job.getTemplate().getSystemPrompt(),
                    sectionContext,
                    sections  // 이전 섹션들을 문맥으로 전달
                );
                sections.add(content);
                jobRepository.save(job);
            }
            job.setGeneratedSections(toJson(sections));

            // Phase 3: REVIEW (선택적)
            if (job.isReviewEnabled()) {
                updateStatus(job, REVIEWING);
                sections = reviewAndRefine(job, outline, sections);
            }

            // Phase 4: RENDER
            updateStatus(job, RENDERING);
            emitProgress("최종 문서를 생성하고 있습니다...");
            String outputPath = renderer.render(job.getTemplate(), outline, sections);
            job.setOutputFilePath(outputPath);

            updateStatus(job, COMPLETE);
            emitComplete(job);

        } catch (Exception e) {
            job.setErrorMessage(e.getMessage());
            updateStatus(job, FAILED);
            emitError(e.getMessage());
        }
    }
}
```

---

## 4. RAG 연동

기존 `SearchService`를 재활용하여 문서 생성에 필요한 컨텍스트를 검색한다.

### 4-1. 전체 문서 컨텍스트 (Phase 1)

```java
private List<String> searchRelevantDocs(GenerationJob job) {
    // 사용자 입력 전체를 쿼리로 사용
    // 태그/컬렉션 필터 적용 (사용자가 선택한 경우)
    SearchResult result = searchService.search(
        job.getUserInput(),
        job.getUser().getId(),
        job.getTagIds(),
        job.getCollectionIds()
    );
    return result.getChunks().stream()
        .map(chunk -> chunk.getContent())
        .toList();
}
```

### 4-2. 섹션별 컨텍스트 (Phase 2)

```java
private List<String> searchForSection(GenerationJob job, SectionPlan plan) {
    // 섹션 제목 + 핵심 포인트를 쿼리로 사용 → 더 정밀한 검색
    String query = plan.heading() + " " + String.join(" ", plan.keyPoints());
    SearchResult result = searchService.search(
        query,
        job.getUser().getId(),
        job.getTagIds(),
        job.getCollectionIds()
    );
    return result.getChunks().stream()
        .map(chunk -> chunk.getContent())
        .toList();
}
```

---

## 5. 비동기 실행 및 진행률

### 5-1. 비동기 처리

- `@Async` 어노테이션으로 별도 스레드에서 실행
- 기존 `IngestionPipeline`의 비동기 패턴과 동일
- `GenerationJob` 엔티티로 상태 영속화 → 서버 재시작 후에도 상태 추적 가능

### 5-2. SSE 진행률 스트리밍

기존 `ChatController`의 SSE 패턴을 재사용한다.

```java
// GenerationController
@GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamProgress(@PathVariable UUID id) {
    SseEmitter emitter = new SseEmitter(600_000L); // 10분 타임아웃
    generationService.registerEmitter(id, emitter);
    return emitter;
}
```

**이벤트 타입:**
- `status` — 단계 변경 (PLANNING → GENERATING → ...)
- `progress` — 섹션 진행률 (2/5)
- `section_complete` — 개별 섹션 완료 (미리보기 포함)
- `complete` — 전체 완료 (다운로드 URL)
- `error` — 오류 발생

---

## 6. 에러 처리 및 재시도

### 6-1. 섹션 단위 재시도

```java
private SectionContent generateWithRetry(SectionPlan plan, ..., int maxRetries) {
    for (int attempt = 0; attempt < maxRetries; attempt++) {
        try {
            SectionContent content = contentGenerator.generateSection(plan, ...);
            if (isValid(content, plan)) {
                return content;
            }
            // 유효하지 않으면 재시도 (피드백 포함)
        } catch (Exception e) {
            if (attempt == maxRetries - 1) throw e;
        }
    }
}
```

### 6-2. 부분 실패 처리

- 개별 섹션 실패 시 해당 섹션만 재생성 (전체 재시작 불필요)
- `generatedSections` JSONB에 중간 결과 저장 → 이어서 생성 가능
- 최종 렌더링 실패 시 콘텐츠는 보존 → 다른 포맷으로 재시도 가능
