# Plan 05 — 품질 관리

AI 생성 문서의 품질을 보장하기 위한 검증, 리뷰, 피드백 루프를 설계한다.

---

## 1. 품질 관리 계층

```
┌─ Layer 1: 스키마 검증 ─────────────────────────┐
│  AI 출력이 기대하는 JSON 구조에 맞는지 확인        │
│  (필수 필드, 타입, 길이 제한)                     │
└────────────────────────────────────────────────┘
          ↓
┌─ Layer 2: 콘텐츠 검증 ─────────────────────────┐
│  내용 수준의 기본 체크                            │
│  (빈 섹션, 최소 길이, 중복 내용)                   │
└────────────────────────────────────────────────┘
          ↓
┌─ Layer 3: AI 리뷰 (선택적) ────────────────────┐
│  별도 AI 호출로 생성된 콘텐츠를 평가               │
│  (정확성, 완성도, 톤, 일관성)                     │
└────────────────────────────────────────────────┘
          ↓
┌─ Layer 4: 사용자 피드백 ───────────────────────┐
│  미리보기 후 사용자가 수정 요청                    │
│  (특정 섹션 재생성, 톤 변경, 내용 추가)            │
└────────────────────────────────────────────────┘
```

---

## 2. Layer 1: 스키마 검증

Spring AI `.entity()`가 JSON 파싱에 실패하면 자동으로 예외를 발생시킨다.
추가로 템플릿의 `sectionSchema`와 대조하여 검증한다.

```java
@Component
public class SchemaValidator {

    /**
     * AI가 생성한 섹션이 템플릿 스키마에 정의된 제약을 만족하는지 검증
     */
    public ValidationResult validate(SectionContent content, SectionSchema schema) {
        List<String> errors = new ArrayList<>();

        // 필수 필드 체크
        if (schema.isRequired() && isBlank(content.content())) {
            errors.add("필수 섹션 '%s'의 본문이 비어 있습니다.".formatted(schema.getTitle()));
        }

        // 최대 길이 체크
        if (schema.getMaxLength() > 0 && content.content().length() > schema.getMaxLength()) {
            errors.add("섹션 '%s'이 최대 길이(%d자)를 초과합니다: %d자"
                .formatted(schema.getTitle(), schema.getMaxLength(), content.content().length()));
        }

        // 최소 길이 체크 (의미 있는 콘텐츠인지)
        if (schema.isRequired() && content.content().length() < 50) {
            errors.add("섹션 '%s'의 내용이 너무 짧습니다 (50자 미만).".formatted(schema.getTitle()));
        }

        // 서브섹션 포함 체크
        if (schema.getSubSections() != null) {
            for (String sub : schema.getSubSections()) {
                if (!content.content().contains(sub)) {
                    errors.add("서브섹션 '%s'이 누락되었습니다.".formatted(sub));
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }
}
```

---

## 3. Layer 2: 콘텐츠 검증

```java
@Component
public class ContentValidator {

    public ValidationResult validate(DocumentOutline outline, List<SectionContent> sections) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1) 모든 계획된 섹션이 생성되었는지
        Set<String> generatedKeys = sections.stream()
            .map(SectionContent::key)
            .collect(Collectors.toSet());
        for (SectionPlan plan : outline.sections()) {
            if (!generatedKeys.contains(plan.key())) {
                errors.add("계획된 섹션 '%s'이 생성되지 않았습니다.".formatted(plan.heading()));
            }
        }

        // 2) 섹션 간 중복 내용 감지
        for (int i = 0; i < sections.size(); i++) {
            for (int j = i + 1; j < sections.size(); j++) {
                double similarity = calculateSimilarity(
                    sections.get(i).content(),
                    sections.get(j).content()
                );
                if (similarity > 0.7) {
                    warnings.add("'%s'과 '%s' 섹션의 내용이 70%% 이상 유사합니다."
                        .formatted(sections.get(i).title(), sections.get(j).title()));
                }
            }
        }

        // 3) 참조된 출처가 실제 RAG 검색 결과에 있는지 (환각 방지)
        // → 출처 목록과 실제 검색된 문서 ID 대조

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    private double calculateSimilarity(String text1, String text2) {
        // 간단한 Jaccard 유사도 또는 n-gram 기반
        Set<String> words1 = Set.of(text1.split("\\s+"));
        Set<String> words2 = Set.of(text2.split("\\s+"));
        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);
        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }
}
```

---

## 4. Layer 3: AI 리뷰

별도 AI 호출로 생성된 콘텐츠의 품질을 평가한다.
사용자가 옵션으로 활성화할 수 있다.

### 4-1. 리뷰 Record

```java
record ReviewResult(
    boolean approved,           // 통과 여부
    int qualityScore,           // 1-10 점수
    List<ReviewIssue> issues    // 발견된 문제
) {}

record ReviewIssue(
    String sectionKey,          // 해당 섹션
    String severity,            // critical, major, minor
    String description,         // 문제 설명
    String suggestion           // 개선 제안
) {}
```

### 4-2. 리뷰 프롬프트

```java
@Component
public class ContentReviewService {
    private final ModelClientProvider modelClientProvider;

    public ReviewResult review(DocumentOutline outline, List<SectionContent> sections,
                                String originalInput) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);

        return client.prompt()
            .system("""
                당신은 문서 품질 검토 전문가입니다. 다음 기준으로 평가하세요:

                1. 정확성: 사용자 입력과 참고 자료에 기반한 사실적 내용인가?
                2. 완성도: 각 섹션이 목적에 맞게 충분한 내용을 담고 있는가?
                3. 일관성: 섹션 간 톤, 용어, 논리가 일관적인가?
                4. 구조: 논리적 흐름이 자연스러운가?
                5. 전문성: 도메인에 적합한 전문 용어와 표현을 사용했는가?

                critical: 반드시 수정해야 할 중대한 오류
                major: 품질을 크게 저하시키는 문제
                minor: 개선하면 좋은 사소한 문제
                """)
            .user(u -> u.text("""
                [원본 요청]
                {input}

                [문서 아웃라인]
                {outline}

                [생성된 섹션들]
                {sections}
                """)
                .param("input", originalInput)
                .param("outline", toJson(outline))
                .param("sections", toJson(sections)))
            .call()
            .entity(ReviewResult.class);
    }
}
```

### 4-3. 리뷰 기반 재생성

```java
// GenerationWorkflowService 내

private List<SectionContent> reviewAndRefine(GenerationJob job,
                                              DocumentOutline outline,
                                              List<SectionContent> sections) {
    ReviewResult review = reviewService.review(outline, sections, job.getUserInput());

    if (review.approved() || review.qualityScore() >= 7) {
        return sections;  // 통과
    }

    // critical/major 이슈가 있는 섹션만 재생성
    List<String> sectionsToRegenerate = review.issues().stream()
        .filter(i -> "critical".equals(i.severity()) || "major".equals(i.severity()))
        .map(ReviewIssue::sectionKey)
        .distinct()
        .toList();

    for (String key : sectionsToRegenerate) {
        SectionPlan plan = findPlan(outline, key);
        ReviewIssue issue = findIssue(review, key);

        // 피드백을 포함하여 재생성
        SectionContent regenerated = contentGenerator.regenerateSection(
            plan,
            job.getTemplate().getSystemPrompt(),
            searchForSection(job, plan),
            sections,
            issue.description() + "\n개선 제안: " + issue.suggestion()
        );

        // 기존 섹션 교체
        sections = replaceSectionContent(sections, key, regenerated);
    }

    return sections;
}
```

---

## 5. Layer 4: 사용자 피드백 루프

### 5-1. 섹션별 재생성 API

사용자가 미리보기에서 특정 섹션에 대해 피드백을 주고 재생성을 요청할 수 있다.

```
POST /api/generations/{id}/sections/{sectionKey}/regenerate
{
  "feedback": "이 섹션에 비용 분석을 추가해주세요. 좀 더 구체적인 수치가 필요합니다."
}
```

```java
// GenerationController
@PostMapping("/{id}/sections/{sectionKey}/regenerate")
public ResponseEntity<SectionContent> regenerateSection(
        @PathVariable UUID id,
        @PathVariable String sectionKey,
        @RequestBody RegenerateRequest request) {
    return ResponseEntity.ok(
        generationService.regenerateSection(id, sectionKey, request.feedback())
    );
}
```

### 5-2. 프론트엔드 피드백 UI

```
미리보기 화면에서:

┌─────────────────────────────────────────┐
│ ## 제안 솔루션                           │
│ 본 제안은 다음과 같은 접근법을...           │
│                                         │
│ [이 섹션 재생성] [피드백 입력]              │
│                                         │
│ ┌─────────────────────────────────────┐ │
│ │ 피드백: 비용 분석을 추가해주세요.       │ │
│ │         구체적인 수치가 필요합니다.     │ │
│ │                     [재생성 요청]     │ │
│ └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

---

## 6. 품질 메트릭 및 모니터링

### 6-1. 추적 항목

| 메트릭 | 설명 |
|--------|------|
| 생성 성공률 | 전체 작업 중 COMPLETE 비율 |
| 평균 품질 점수 | AI 리뷰 qualityScore 평균 |
| 재생성 빈도 | 섹션별 재생성 횟수 |
| 템플릿별 성공률 | 어떤 템플릿이 높은 품질을 보이는지 |
| 평균 생성 시간 | 단계별 소요 시간 |

### 6-2. 기존 인프라 활용

- `AuditService`로 생성 이벤트 로깅
- `TokenUsageRepository`로 토큰 사용량 추적
- 어드민 대시보드에 문서 생성 통계 추가

---

## 7. 프롬프트 품질 관리

### 7-1. 템플릿별 시스템 프롬프트 가이드라인

효과적인 시스템 프롬프트 작성을 위한 구조:

```
[역할 정의]
당신은 {도메인} 전문 문서 작성자입니다.

[톤과 스타일]
- 격식체/비격식체 지정
- 전문 용어 수준
- 문장 길이 및 복잡도

[품질 기준]
- 반드시 포함할 요소
- 피해야 할 표현
- 근거 기반 서술 원칙

[출력 형식]
- 마크다운 헤딩 레벨
- 목록/표 사용 가이드
- 참조 표기법
```

### 7-2. 프롬프트 버전 관리

- 템플릿의 `systemPrompt`를 수정할 때 이전 버전 보존 (별도 테이블 또는 JSONB 이력)
- A/B 테스트 가능: 같은 입력으로 다른 프롬프트 버전 비교
- 품질 점수와 프롬프트 버전의 상관관계 분석
