package com.example.rag.generation.renderer;

import com.example.rag.generation.workflow.ContentTable;

import java.util.List;
import java.util.Map;

public record SectionViewModel(
        String key,
        String title,
        String contentHtml,
        List<String> highlights,
        List<ContentTable> tables,
        List<String> references,
        String layoutType,
        Map<String, Object> layoutData
) {}
