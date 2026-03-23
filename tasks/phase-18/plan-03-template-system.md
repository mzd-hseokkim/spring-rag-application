# Plan 03 — 템플릿 시스템

PDF, PPTX, HTML 각 포맷의 렌더링 방식과 템플릿 관리 구조를 정의한다.

---

## 1. 렌더링 아키텍처

```
DocumentRendererService (팩토리)
│
├─ HtmlRenderer      → Thymeleaf 템플릿 → HTML 파일
├─ PdfRenderer        → Thymeleaf 템플릿 → HTML → OpenHTMLtoPDF → PDF 파일
└─ PptxRenderer       → Apache POI XSLF + 마스터 .pptx → PPTX 파일
```

```java
@Service
public class DocumentRendererService {
    private final Map<OutputFormat, Renderer> renderers;

    public String render(DocumentTemplate template, DocumentOutline outline,
                         List<SectionContent> sections) {
        Renderer renderer = renderers.get(template.getOutputFormat());
        return renderer.render(template, outline, sections);
    }
}

public interface Renderer {
    String render(DocumentTemplate template, DocumentOutline outline,
                  List<SectionContent> sections);
}
```

---

## 2. HTML 렌더링

### 2-1. Thymeleaf 템플릿 구조

```
backend/src/main/resources/templates/generation/
├── proposal.html          ← 제안서 템플릿
├── report.html            ← 보고서 템플릿
├── fragments/
│   ├── header.html        ← 공통 헤더
│   ├── footer.html        ← 공통 푸터
│   ├── section.html       ← 섹션 레이아웃
│   └── table.html         ← 표 레이아웃
└── css/
    ├── document-base.css  ← 기본 스타일
    ├── proposal.css       ← 제안서 전용
    └── print.css          ← 인쇄/PDF 전용
```

### 2-2. Thymeleaf 템플릿 예시 (제안서)

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8"/>
    <style th:inline="text">
        /* CSS 인라인 (PDF 변환 호환성을 위해) */
        [[${css}]]
    </style>
</head>
<body>
    <!-- 표지 -->
    <div class="cover-page">
        <h1 th:text="${outline.title}">문서 제목</h1>
        <p th:text="${outline.summary}">요약</p>
        <p class="date" th:text="${#temporals.format(generatedAt, 'yyyy년 MM월 dd일')}"></p>
    </div>

    <!-- 목차 -->
    <div class="toc">
        <h2>목차</h2>
        <ol>
            <li th:each="section : ${sections}">
                <a th:text="${section.title}" th:href="'#' + ${section.key}"></a>
            </li>
        </ol>
    </div>

    <!-- 본문 섹션들 -->
    <div th:each="section : ${sections}" class="section" th:id="${section.key}">
        <h2 th:text="${section.title}">섹션 제목</h2>

        <!-- 본문 (마크다운 → HTML 변환된 상태) -->
        <div th:utext="${section.contentHtml}">본문 내용</div>

        <!-- 핵심 포인트 -->
        <div th:if="${!section.highlights.isEmpty()}" class="highlights">
            <h3>핵심 포인트</h3>
            <ul>
                <li th:each="point : ${section.highlights}" th:text="${point}"></li>
            </ul>
        </div>

        <!-- 표 -->
        <div th:each="table : ${section.tables}" class="data-table">
            <table>
                <caption th:text="${table.caption}"></caption>
                <thead>
                    <tr>
                        <th th:each="header : ${table.headers}" th:text="${header}"></th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="row : ${table.rows}">
                        <td th:each="cell : ${row}" th:text="${cell}"></td>
                    </tr>
                </tbody>
            </table>
        </div>
    </div>

    <!-- 참고 출처 -->
    <div class="references">
        <h2>참고 자료</h2>
        <ul>
            <li th:each="ref : ${allReferences}" th:text="${ref}"></li>
        </ul>
    </div>
</body>
</html>
```

### 2-3. HtmlRenderer 구현

```java
@Component
public class HtmlRenderer implements Renderer {
    private final TemplateEngine templateEngine;
    private final MarkdownConverter markdownConverter;

    @Override
    public String render(DocumentTemplate template, DocumentOutline outline,
                         List<SectionContent> sections) {
        // 마크다운 → HTML 변환
        List<SectionViewModel> viewModels = sections.stream()
            .map(s -> new SectionViewModel(
                s.key(), s.title(),
                markdownConverter.toHtml(s.content()),
                s.highlights(), s.tables(), s.references()
            ))
            .toList();

        Context ctx = new Context();
        ctx.setVariable("outline", outline);
        ctx.setVariable("sections", viewModels);
        ctx.setVariable("generatedAt", LocalDateTime.now());
        ctx.setVariable("css", loadCss(template));

        String html = templateEngine.process(template.getTemplatePath(), ctx);

        // 파일 저장
        String outputPath = generateOutputPath(template, "html");
        Files.writeString(Path.of(outputPath), html);
        return outputPath;
    }
}
```

---

## 3. PDF 렌더링

HTML → PDF 변환. HtmlRenderer의 결과를 OpenHTMLtoPDF로 변환한다.

### 3-1. PdfRenderer 구현

```java
@Component
public class PdfRenderer implements Renderer {
    private final HtmlRenderer htmlRenderer;

    @Override
    public String render(DocumentTemplate template, DocumentOutline outline,
                         List<SectionContent> sections) {
        // 1) HTML 생성 (Thymeleaf)
        String html = htmlRenderer.renderToString(template, outline, sections);

        // 2) HTML → PDF 변환 (OpenHTMLtoPDF)
        String outputPath = generateOutputPath(template, "pdf");
        try (OutputStream os = new FileOutputStream(outputPath)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, "/");

            // 한글 폰트 설정
            builder.useFont(
                new File("fonts/NotoSansKR-Regular.ttf"),
                "Noto Sans KR"
            );

            builder.toStream(os);
            builder.run();
        }
        return outputPath;
    }
}
```

### 3-2. PDF 스타일 고려사항

- CSS `@page` 규칙으로 페이지 크기, 여백, 페이지 번호 설정
- `page-break-before: always` 로 섹션별 페이지 구분
- 한글 폰트 임베딩 필수 (Noto Sans KR 등)
- 이미지는 base64 인코딩 또는 절대 경로로 참조

```css
@page {
    size: A4;
    margin: 2.5cm;
    @bottom-center {
        content: counter(page) " / " counter(pages);
    }
}

.cover-page { page-break-after: always; }
.section { page-break-before: always; }
```

---

## 4. PPTX 렌더링

### 4-1. 마스터 템플릿 방식

PowerPoint에서 디자인한 `.pptx` 마스터 파일을 사용한다.
코드는 레이아웃 선택 + 플레이스홀더 채우기만 담당한다.

```
backend/src/main/resources/templates/generation/pptx/
├── proposal-master.pptx    ← 제안서 마스터 (디자이너가 PowerPoint로 제작)
└── report-master.pptx      ← 보고서 마스터
```

**마스터 파일에 포함되는 슬라이드 레이아웃:**
- `TITLE` — 표지 슬라이드
- `SECTION_HEADER` — 섹션 구분 슬라이드
- `TITLE_AND_CONTENT` — 제목 + 본문
- `TWO_CONTENT` — 2단 레이아웃
- `TABLE` — 표 슬라이드
- `BLANK` — 빈 슬라이드 (이미지 등)

### 4-2. PptxRenderer 구현

```java
@Component
public class PptxRenderer implements Renderer {

    @Override
    public String render(DocumentTemplate template, DocumentOutline outline,
                         List<SectionContent> sections) {
        // 1) 마스터 템플릿 로드
        try (InputStream is = loadMasterTemplate(template);
             XMLSlideShow ppt = new XMLSlideShow(is)) {

            XSLFSlideMaster master = ppt.getSlideMasters().get(0);

            // 2) 표지 슬라이드
            XSLFSlideLayout titleLayout = master.getLayout(SlideLayout.TITLE);
            XSLFSlide titleSlide = ppt.createSlide(titleLayout);
            titleSlide.getPlaceholder(0).setText(outline.title());
            titleSlide.getPlaceholder(1).setText(outline.summary());

            // 3) 섹션별 슬라이드 생성
            for (SectionContent section : sections) {
                // 섹션 헤더
                XSLFSlideLayout sectionLayout = master.getLayout(SlideLayout.SECTION_HEADER);
                XSLFSlide sectionSlide = ppt.createSlide(sectionLayout);
                sectionSlide.getPlaceholder(0).setText(section.title());

                // 본문 슬라이드 (긴 내용은 여러 슬라이드로 분할)
                List<String> chunks = splitContentForSlides(section.content(), 300);
                for (String chunk : chunks) {
                    XSLFSlideLayout contentLayout = master.getLayout(SlideLayout.TITLE_AND_CONTENT);
                    XSLFSlide contentSlide = ppt.createSlide(contentLayout);
                    contentSlide.getPlaceholder(0).setText(section.title());
                    contentSlide.getPlaceholder(1).setText(chunk);
                }

                // 표 슬라이드 (있을 경우)
                for (ContentTable table : section.tables()) {
                    createTableSlide(ppt, master, section.title(), table);
                }

                // 핵심 포인트 슬라이드
                if (!section.highlights().isEmpty()) {
                    createHighlightsSlide(ppt, master, section.title(), section.highlights());
                }
            }

            // 4) 초기 빈 슬라이드 제거 (마스터 템플릿에 포함된 샘플)
            removeTemplateSampleSlides(ppt);

            // 5) 저장
            String outputPath = generateOutputPath(template, "pptx");
            try (FileOutputStream out = new FileOutputStream(outputPath)) {
                ppt.write(out);
            }
            return outputPath;
        }
    }

    private void createTableSlide(XMLSlideShow ppt, XSLFSlideMaster master,
                                   String title, ContentTable tableData) {
        XSLFSlide slide = ppt.createSlide(master.getLayout(SlideLayout.BLANK));

        // 제목 텍스트박스
        XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setText(title + " - " + tableData.caption());
        titleBox.setAnchor(new Rectangle2D.Double(50, 20, 620, 40));

        // 표 생성
        XSLFTable tbl = slide.createTable(
            tableData.rows().size() + 1,  // +1 for header
            tableData.headers().size()
        );
        tbl.setAnchor(new Rectangle2D.Double(50, 80, 620, 400));

        // 헤더
        for (int c = 0; c < tableData.headers().size(); c++) {
            tbl.getCell(0, c).setText(tableData.headers().get(c));
        }
        // 데이터
        for (int r = 0; r < tableData.rows().size(); r++) {
            for (int c = 0; c < tableData.rows().get(r).size(); c++) {
                tbl.getCell(r + 1, c).setText(tableData.rows().get(r).get(c));
            }
        }
    }
}
```

### 4-3. PPTX 콘텐츠 분할

AI가 생성한 긴 텍스트를 슬라이드 크기에 맞게 분할한다.

```java
private List<String> splitContentForSlides(String content, int maxCharsPerSlide) {
    // 단락 단위로 분할 (문장 중간에서 끊지 않음)
    List<String> paragraphs = Arrays.asList(content.split("\n\n"));
    List<String> slides = new ArrayList<>();
    StringBuilder current = new StringBuilder();

    for (String para : paragraphs) {
        if (current.length() + para.length() > maxCharsPerSlide && current.length() > 0) {
            slides.add(current.toString().trim());
            current = new StringBuilder();
        }
        current.append(para).append("\n\n");
    }
    if (current.length() > 0) {
        slides.add(current.toString().trim());
    }
    return slides;
}
```

---

## 5. 템플릿 관리

### 5-1. 시스템 기본 템플릿

초기 배포 시 Flyway 데이터 마이그레이션으로 기본 템플릿을 삽입한다.

```sql
-- V18_1__insert_default_templates.sql

INSERT INTO document_template (name, description, output_format, section_schema, system_prompt, template_path, is_public)
VALUES
('기술 제안서', 'RFP 기반 기술 제안서를 생성합니다', 'PDF',
 '{"sections":[...]}',
 '당신은 전문 기술 제안서 작성자입니다. 명확하고 설득력 있는 문장을 사용하세요...',
 'generation/proposal', true),

('사업 보고서', '사업 현황 보고서를 생성합니다', 'PDF',
 '{"sections":[...]}',
 '당신은 비즈니스 보고서 작성 전문가입니다...',
 'generation/report', true),

('프레젠테이션', '발표 자료를 PPTX로 생성합니다', 'PPTX',
 '{"sections":[...]}',
 '당신은 프레젠테이션 전문가입니다. 슬라이드에 적합한 간결한 문장을 사용하세요...',
 'pptx/proposal-master', true);
```

### 5-2. 어드민 템플릿 관리 UI

- 템플릿 목록 조회/검색
- 섹션 스키마 편집 (JSON 에디터)
- 시스템 프롬프트 편집 (텍스트 에디터)
- PPTX 마스터 파일 업로드
- 미리보기 (샘플 데이터로 테스트 생성)

---

## 6. 마크다운 → HTML 변환

AI가 생성한 본문은 마크다운 형식을 허용한다.
렌더링 전에 HTML로 변환하여 Thymeleaf 템플릿에 전달한다.

```java
@Component
public class MarkdownConverter {
    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();

    public String toHtml(String markdown) {
        Node document = parser.parse(markdown);
        return renderer.render(document);
    }
}
```

의존성:
```kotlin
implementation("org.commonmark:commonmark:0.24.0")
```
