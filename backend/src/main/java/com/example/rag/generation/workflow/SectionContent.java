package com.example.rag.generation.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SectionContent(
        String key,
        String title,
        String content,
        List<String> highlights,
        List<ContentTable> tables,
        List<String> references
) {}
