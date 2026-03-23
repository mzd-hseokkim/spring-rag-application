package com.example.rag.generation.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentOutline(
        String title,
        String summary,
        List<SectionPlan> sections
) {}
