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
        Map<String, Object> layoutData,
        String governingMessage,
        String visualGuide
) {
    private static final java.util.Set<String> VALID_LAYOUTS = java.util.Set.of(
            "TEXT_FULL", "TEXT_TABLE", "COMPARE_2COL", "PROCESS_HORIZONTAL",
            "KPI_CARDS", "TABLE_FULL", "TEXT_IMAGE", "IMAGE_FULL");

    public String fragmentName() {
        return VALID_LAYOUTS.contains(layoutType) ? layoutType : "TEXT_FULL";
    }
}
