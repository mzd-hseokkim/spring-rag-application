package kr.co.mz.ragservice.generation.renderer;

import kr.co.mz.ragservice.generation.template.DocumentTemplate;
import kr.co.mz.ragservice.generation.workflow.DocumentOutline;
import kr.co.mz.ragservice.generation.workflow.SectionContent;

import java.util.List;
import java.util.UUID;

public interface Renderer {

    String render(DocumentTemplate template, DocumentOutline outline,
                  List<SectionContent> sections, UUID userId, UUID jobId);
}
