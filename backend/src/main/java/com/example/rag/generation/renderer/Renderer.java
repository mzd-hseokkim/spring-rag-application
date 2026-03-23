package com.example.rag.generation.renderer;

import com.example.rag.generation.template.DocumentTemplate;
import com.example.rag.generation.workflow.DocumentOutline;
import com.example.rag.generation.workflow.SectionContent;

import java.util.List;
import java.util.UUID;

public interface Renderer {

    String render(DocumentTemplate template, DocumentOutline outline,
                  List<SectionContent> sections, UUID userId, UUID jobId);
}
