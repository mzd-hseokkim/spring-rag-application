package com.example.rag.generation.renderer;

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
import java.util.List;
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
                        s.references()))
                .toList();

        List<String> allReferences = sections.stream()
                .flatMap(s -> s.references().stream())
                .distinct()
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
            throw new RuntimeException("Failed to write generated HTML file", e);
        }
    }
}
