package com.example.rag.generation.renderer;

import com.example.rag.generation.OutputFormat;
import com.example.rag.generation.template.DocumentTemplate;
import com.example.rag.generation.workflow.DocumentOutline;
import com.example.rag.generation.workflow.SectionContent;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentRendererService {

    private final Map<OutputFormat, Renderer> renderers;

    public DocumentRendererService(HtmlRenderer htmlRenderer) {
        // MVP: HTML만 지원. PDF/PPTX는 향후 추가
        this.renderers = Map.of(OutputFormat.HTML, htmlRenderer);
    }

    public String render(DocumentTemplate template, DocumentOutline outline,
                         List<SectionContent> sections, UUID userId, UUID jobId) {
        OutputFormat format = template.getOutputFormat();
        Renderer renderer = renderers.get(format);
        if (renderer == null) {
            throw new UnsupportedOperationException("Output format not yet supported: " + format);
        }
        return renderer.render(template, outline, sections, userId, jobId);
    }
}
