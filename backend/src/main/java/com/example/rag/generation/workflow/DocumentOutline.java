package com.example.rag.generation.workflow;

import com.example.rag.generation.dto.OutlineNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentOutline(
        String title,
        String summary,
        List<SectionPlan> sections,
        List<OutlineNode> outlineNodes
) {
    public DocumentOutline(String title, String summary, List<SectionPlan> sections) {
        this(title, summary, sections, null);
    }
}
