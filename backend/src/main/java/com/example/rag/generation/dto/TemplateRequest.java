package com.example.rag.generation.dto;

import com.example.rag.generation.OutputFormat;

public record TemplateRequest(
        String name,
        String description,
        OutputFormat outputFormat,
        String sectionSchema,
        String systemPrompt,
        String templatePath,
        boolean isPublic
) {}
