# Plan 01 — 기반 인프라

문서 생성 기능에 필요한 엔티티, DB 스키마, 의존성, REST API를 정의한다.

---

## 1. 새로운 의존성

```kotlin
// build.gradle.kts
implementation("org.apache.poi:poi-ooxml:5.5.1")                        // PPTX 생성
implementation("org.springframework.boot:spring-boot-starter-thymeleaf") // HTML 템플릿
implementation("io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.37")   // HTML → PDF
```

---

## 2. 엔티티 설계

### 2-1. DocumentTemplate (문서 템플릿)

템플릿은 문서의 구조와 스타일을 정의한다.

```java
@Entity
@Table(name = "document_template")
public class DocumentTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;                    // 템플릿 이름 (예: "기술 제안서")

    @Column(length = 1000)
    private String description;             // 템플릿 설명

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutputFormat outputFormat;      // PDF, PPTX, HTML

    @Column(columnDefinition = "jsonb")
    private String sectionSchema;           // 섹션 구조 정의 (JSON Schema)

    @Column(columnDefinition = "text")
    private String systemPrompt;            // AI 지시사항 (톤, 스타일, 제약 등)

    @Column(length = 500)
    private String templatePath;            // Thymeleaf 파일 경로 or PPTX 마스터 파일 경로

    @Column(nullable = false)
    private boolean isPublic = false;       // 공용 템플릿 여부

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;                   // 소유자 (null이면 시스템 템플릿)

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

**OutputFormat enum:**
```java
public enum OutputFormat {
    PDF, PPTX, HTML
}
```

**sectionSchema 예시 (제안서 템플릿):**
```json
{
  "sections": [
    {
      "key": "executive_summary",
      "title": "요약",
      "required": true,
      "maxLength": 500,
      "prompt": "제안의 핵심 내용을 3-5문장으로 요약하세요."
    },
    {
      "key": "problem_analysis",
      "title": "문제 분석",
      "required": true,
      "maxLength": 2000,
      "prompt": "제안요청서에 명시된 문제를 분석하고 핵심 이슈를 도출하세요.",
      "subSections": ["현황 분석", "핵심 이슈", "개선 방향"]
    },
    {
      "key": "proposed_solution",
      "title": "제안 솔루션",
      "required": true,
      "maxLength": 3000,
      "prompt": "문제를 해결할 구체적인 방안을 제시하세요."
    },
    {
      "key": "timeline",
      "title": "추진 일정",
      "required": false,
      "type": "table",
      "prompt": "단계별 추진 일정을 표로 작성하세요."
    }
  ]
}
```

### 2-2. GenerationJob (생성 작업)

문서 생성의 진행 상태를 추적한다.

```java
@Entity
@Table(name = "generation_job")
public class GenerationJob {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GenerationStatus status;        // PLANNING, GENERATING, REVIEWING, RENDERING, COMPLETE, FAILED

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private DocumentTemplate template;

    @Column(columnDefinition = "text", nullable = false)
    private String userInput;               // 사용자 원본 입력 (제안요청서 내용 등)

    @Column(columnDefinition = "jsonb")
    private String outline;                 // Phase 1 결과: 문서 아웃라인

    @Column(columnDefinition = "jsonb")
    private String generatedSections;       // Phase 2 결과: 생성된 섹션들

    @Column(length = 500)
    private String outputFilePath;          // 최종 생성 파일 경로

    private int currentSection;             // 현재 처리 중인 섹션 인덱스
    private int totalSections;              // 전체 섹션 수

    @Column(length = 1000)
    private String errorMessage;            // 실패 시 에러 메시지

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    // 선택적: 채팅 대화 기반 생성 시
    @Column(name = "conversation_id")
    private UUID conversationId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

**GenerationStatus enum:**
```java
public enum GenerationStatus {
    PLANNING,       // 아웃라인 생성 중
    GENERATING,     // 섹션 콘텐츠 생성 중
    REVIEWING,      // AI 리뷰 중
    RENDERING,      // 최종 파일 렌더링 중
    COMPLETE,       // 완료
    FAILED          // 실패
}
```

---

## 3. DB 스키마 (Flyway)

```sql
-- V18__create_document_generation_tables.sql

CREATE TABLE document_template (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(1000),
    output_format   VARCHAR(20) NOT NULL,
    section_schema  JSONB,
    system_prompt   TEXT,
    template_path   VARCHAR(500),
    is_public       BOOLEAN NOT NULL DEFAULT false,
    user_id         UUID REFERENCES app_user(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE generation_job (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status              VARCHAR(20) NOT NULL,
    template_id         UUID NOT NULL REFERENCES document_template(id),
    user_input          TEXT NOT NULL,
    outline             JSONB,
    generated_sections  JSONB,
    output_file_path    VARCHAR(500),
    current_section     INT NOT NULL DEFAULT 0,
    total_sections      INT NOT NULL DEFAULT 0,
    error_message       VARCHAR(1000),
    user_id             UUID NOT NULL REFERENCES app_user(id),
    conversation_id     UUID,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_generation_job_user ON generation_job (user_id);
CREATE INDEX idx_generation_job_status ON generation_job (status);
CREATE INDEX idx_document_template_user ON document_template (user_id);
```

---

## 4. REST API 설계

### 4-1. 템플릿 API

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/templates` | 사용 가능한 템플릿 목록 (본인 + 공용) |
| GET | `/api/templates/{id}` | 템플릿 상세 |
| POST | `/api/templates` | 템플릿 생성 (어드민) |
| PUT | `/api/templates/{id}` | 템플릿 수정 (어드민) |
| DELETE | `/api/templates/{id}` | 템플릿 삭제 (어드민) |

### 4-2. 문서 생성 API

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/generations` | 문서 생성 시작 |
| GET | `/api/generations/{id}` | 생성 작업 상태 조회 |
| GET | `/api/generations/{id}/stream` | SSE 진행률 스트리밍 |
| GET | `/api/generations/{id}/download` | 생성된 파일 다운로드 |
| GET | `/api/generations/{id}/preview` | HTML 미리보기 |
| GET | `/api/generations` | 내 생성 이력 목록 |
| DELETE | `/api/generations/{id}` | 생성 작업 삭제 |

### 4-3. 요청/응답 예시

**POST /api/generations 요청:**
```json
{
  "templateId": "uuid-of-template",
  "userInput": "다음 제안요청서를 기반으로 제안서를 작성해주세요:\n\n[제안요청서 내용...]",
  "conversationId": "optional-uuid",
  "options": {
    "includeReview": true,
    "tagIds": ["uuid1"],
    "collectionIds": ["uuid2"]
  }
}
```

**SSE 이벤트 (GET /api/generations/{id}/stream):**
```
event: status
data: {"status": "PLANNING", "message": "문서 아웃라인을 생성하고 있습니다..."}

event: progress
data: {"currentSection": 2, "totalSections": 5, "sectionTitle": "문제 분석"}

event: section_complete
data: {"sectionKey": "problem_analysis", "preview": "..."}

event: complete
data: {"outputFormat": "PDF", "downloadUrl": "/api/generations/{id}/download"}

event: error
data: {"message": "섹션 생성 중 오류가 발생했습니다."}
```

---

## 5. 파일 저장소

생성된 파일은 기존 문서 저장 방식과 동일하게 처리한다.

```
storage/
├── documents/          ← 기존 업로드 문서
└── generations/        ← 생성된 문서
    └── {userId}/
        └── {jobId}.{pdf|pptx|html}
```

---

## 6. 패키지 구조

```
com.example.rag.generation/
├── GenerationController.java
├── GenerationService.java
├── GenerationJob.java
├── GenerationJobRepository.java
├── GenerationStatus.java
├── dto/
│   ├── GenerationRequest.java
│   ├── GenerationResponse.java
│   └── GenerationProgressEvent.java
├── template/
│   ├── DocumentTemplate.java
│   ├── DocumentTemplateRepository.java
│   ├── TemplateController.java
│   └── TemplateService.java
├── workflow/
│   ├── GenerationWorkflowService.java    ← 4단계 파이프라인 오케스트레이션
│   ├── ContentGeneratorService.java      ← AI 콘텐츠 생성
│   └── DocumentRendererService.java      ← 포맷별 렌더링
└── renderer/
    ├── HtmlRenderer.java
    ├── PdfRenderer.java
    └── PptxRenderer.java
```
