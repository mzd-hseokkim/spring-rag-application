package com.example.rag.generation.renderer;

import com.example.rag.generation.workflow.ContentTable;

import java.util.List;

public record SectionViewModel(
        String key,
        String title,
        String contentHtml,
        List<String> highlights,
        List<ContentTable> tables,
        List<String> references
) {}
