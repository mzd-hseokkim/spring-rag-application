package com.example.rag.generation.renderer;

import com.example.rag.common.RagException;
import com.example.rag.generation.dto.OutlineNode;
import com.example.rag.generation.template.DocumentTemplate;
import com.example.rag.generation.workflow.DocumentOutline;
import com.example.rag.generation.workflow.SectionContent;
import com.example.rag.generation.workflow.SectionPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class HtmlRenderer implements Renderer {

    private final TemplateEngine templateEngine;
    private final MarkdownConverter markdownConverter;
    private final Path storageBase;

    public HtmlRenderer(TemplateEngine templateEngine,
                        MarkdownConverter markdownConverter,
                        @Value("${app.upload.generation-dir:./uploads/generations}") String storagePath) {
        this.templateEngine = templateEngine;
        this.markdownConverter = markdownConverter;
        this.storageBase = Path.of(storagePath);
    }

    @Override
    public String render(DocumentTemplate template, DocumentOutline outline,
                         List<SectionContent> sections, UUID userId, UUID jobId) {
        // outline에서 depth별 제목 맵 구성
        Map<String, String> depth1Titles = new LinkedHashMap<>();
        Map<String, String> depth2Titles = new LinkedHashMap<>();
        for (SectionPlan plan : outline.sections()) {
            String[] parts = plan.key().split("\\.");
            if (parts.length == 1) {
                depth1Titles.put(plan.key(), plan.heading());
            } else if (parts.length == 2) {
                depth2Titles.put(plan.key(), plan.heading());
            }
        }
        // 1 depth 목차만 필터 (간지 + TOC용)
        List<SectionPlan> tocPlans = outline.sections().stream()
                .filter(p -> !p.key().contains("."))
                .toList();

        // breadcrumb 계산
        List<SectionViewModel> viewModels = sections.stream()
                .map(s -> new SectionViewModel(
                        s.key(),
                        s.title(),
                        markdownConverter.toHtml(s.content()),
                        s.highlights(),
                        s.tables(),
                        s.references(),
                        s.layoutType() != null ? s.layoutType() : "TEXT_FULL",
                        convertLayoutDataNewlines(s.layoutData()),
                        s.governingMessage() != null ? s.governingMessage() : "",
                        s.visualGuide() != null ? s.visualGuide() : "",
                        buildBreadcrumb(s.key(), depth1Titles, depth2Titles)))
                .toList();

        // chapters 구성 (간지용)
        List<ChapterViewModel> chapters = buildChapters(tocPlans, depth2Titles);

        List<String> allReferences = sections.stream()
                .flatMap(s -> s.references().stream())
                .distinct()
                .map(ref -> ref.replaceAll("(https?://[^\\s)]+)",
                        "<a href=\"$1\" target=\"_blank\" rel=\"noopener noreferrer\" style=\"color:#2563eb;text-decoration:underline\">$1</a>"))
                .toList();

        Context ctx = new Context();
        ctx.setVariable("outline", outline);
        ctx.setVariable("sections", viewModels);
        ctx.setVariable("tocSections", tocPlans);
        ctx.setVariable("chapters", chapters);
        ctx.setVariable("allReferences", allReferences);
        ctx.setVariable("generatedAt", LocalDateTime.now());

        String templatePath = template.getTemplatePath();
        if (templatePath == null || templatePath.isBlank()) {
            templatePath = "generation/proposal";
        }
        String html = templateEngine.process(templatePath, ctx);

        Path outputDir = storageBase.resolve(userId.toString());
        try {
            Files.createDirectories(outputDir);
            Path outputFile = outputDir.resolve(jobId + ".html");
            Files.writeString(outputFile, html);
            return outputFile.toString();
        } catch (IOException e) {
            throw new RagException("Failed to write generated HTML file", e);
        }
    }

    /**
     * 섹션 key에서 상위 경로 breadcrumb 문자열 생성.
     * "1.2" → "I. 사업 이해 및 전략"
     * "1.2.3" → "I. 사업 이해 및 전략 > 1.2 세부 제목"
     */
    private String buildBreadcrumb(String key, Map<String, String> depth1Titles, Map<String, String> depth2Titles) {
        String[] parts = key.split("\\.");
        if (parts.length < 2) return "";

        String d1Key = parts[0];
        String d1Title = depth1Titles.getOrDefault(d1Key, "");
        if (d1Title.isEmpty()) return "";

        if (parts.length == 2) {
            return d1Title;
        }
        // 3 depth: "1.2.3" → depth1 > depth2
        String d2Key = parts[0] + "." + parts[1];
        String d2Title = depth2Titles.getOrDefault(d2Key, "");
        if (d2Title.isEmpty()) return d1Title;
        return d1Title + "  >  " + d2Title;
    }

    /**
     * 1 depth 목차(SectionPlan) 기준으로 간지 ViewModel 목록을 구성한다.
     * 각 chapter에는 해당 chapter에 속하는 2 depth 하위 항목 목록이 포함된다.
     * outline의 depth2Titles에서 가져오므로, 2 depth가 leaf가 아니어도 정상 표시된다.
     */
    private List<ChapterViewModel> buildChapters(List<SectionPlan> tocPlans, Map<String, String> depth2Titles) {
        List<ChapterViewModel> chapters = new ArrayList<>();
        for (SectionPlan plan : tocPlans) {
            String prefix = plan.key() + ".";
            List<ChapterViewModel.SubItem> subItems = depth2Titles.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(prefix))
                    .map(e -> new ChapterViewModel.SubItem(e.getKey(), e.getValue()))
                    .toList();
            chapters.add(new ChapterViewModel(plan.key(), plan.heading(), subItems));
        }
        return chapters;
    }

    /**
     * layoutData 내 문자열 값의 \n을 <br>로 변환하고, · 구분자를 줄바꿈으로 치환.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertLayoutDataNewlines(Map<String, Object> layoutData) {
        if (layoutData == null || layoutData.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : layoutData.entrySet()) {
            result.put(entry.getKey(), convertValueNewlines(entry.getValue()));
        }
        return result;
    }

    private Object convertValueNewlines(Object value) {
        if (value instanceof String s) {
            return convertStringNewlines(s);
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (var e : map.entrySet()) {
                converted.put(String.valueOf(e.getKey()), convertValueNewlines(e.getValue()));
            }
            return converted;
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) return list;
            // String만 담긴 리스트 → <br>로 연결
            if (list.getFirst() instanceof String) {
                return list.stream()
                        .map(item -> convertStringNewlines((String) item))
                        .collect(java.util.stream.Collectors.joining("<br>"));
            }
            // Map 등 복합 객체 리스트 → 재귀 변환 후 리스트 유지
            return list.stream().map(this::convertValueNewlines).toList();
        }
        return value;
    }

    private String convertStringNewlines(String s) {
        // 이모지 제거
        String result = s.replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2300}-\\x{23FF}\\x{2600}-\\x{27BF}\\x{2B50}-\\x{2BFF}\\x{FE00}-\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}\\x{2702}-\\x{27B0}\\x{1F900}-\\x{1F9FF}\\x{1FA00}-\\x{1FA6F}\\x{1FA70}-\\x{1FAFF}]+\\s?", "");
        // \\n 리터럴과 실제 줄바꿈을 <br>로 변환
        result = result.replace("\\n", "\n").replace("\n", "<br>");
        // 줄 시작 또는 , 뒤의 · / • 만 bullet로 인식하여 개행 (단어 중간의 가운뎃점은 무시)
        result = result.replaceAll("(?<=<br>|^)[,\\s]*[·•]\\s?", "<br>· ");
        result = result.replaceAll(",\\s*[·•]\\s?", "<br>· ");
        // 선행 <br> 중복 제거
        result = result.replace("<br><br>", "<br>");
        // 맨 앞 <br> 제거
        if (result.startsWith("<br>")) result = result.substring(4);
        return result;
    }
}
