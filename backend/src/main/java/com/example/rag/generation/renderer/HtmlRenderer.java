package com.example.rag.generation.renderer;

import com.example.rag.common.RagException;
import com.example.rag.generation.template.DocumentTemplate;
import com.example.rag.generation.workflow.DocumentOutline;
import com.example.rag.generation.workflow.SectionContent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
                        s.visualGuide() != null ? s.visualGuide() : ""))
                .toList();

        List<String> allReferences = sections.stream()
                .flatMap(s -> s.references().stream())
                .distinct()
                .map(ref -> ref.replaceAll("(https?://[^\\s)]+)",
                        "<a href=\"$1\" target=\"_blank\" rel=\"noopener noreferrer\" style=\"color:#2563eb;text-decoration:underline\">$1</a>"))
                .toList();

        Context ctx = new Context();
        ctx.setVariable("outline", outline);
        ctx.setVariable("sections", viewModels);
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
     * layoutData 내 문자열 값의 \n을 <br>로 변환하고, · 구분자를 줄바꿈으로 치환.
     */
    private Map<String, Object> convertLayoutDataNewlines(Map<String, Object> layoutData) {
        if (layoutData == null || layoutData.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : layoutData.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                result.put(entry.getKey(), s
                        .replace("·", "<br>·")
                        .replace("\n", "<br>")
                        .replace("\\n", "<br>"));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }
}
