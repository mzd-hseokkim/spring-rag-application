package com.example.rag.generation.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SectionContent(
        String key,
        String title,
        String content,
        List<String> highlights,
        List<ContentTable> tables,
        List<String> references,
        String layoutType,
        Map<String, Object> layoutData,
        String governingMessage,
        String visualGuide,
        List<String> sources
) {
    public SectionContent {
        if (layoutType == null || layoutType.isBlank()) layoutType = "TEXT_FULL";
        if (layoutData == null) layoutData = Map.of();
        if (governingMessage == null) governingMessage = "";
        if (visualGuide == null) visualGuide = "";
        if (sources == null) sources = List.of();
    }
}
