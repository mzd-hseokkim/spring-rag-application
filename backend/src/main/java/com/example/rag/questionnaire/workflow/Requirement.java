package com.example.rag.questionnaire.workflow;

public record Requirement(
        String id,
        String category,
        String item,
        String description,
        String importance
) {}
