package com.example.rag.generation.dto;

import com.example.rag.generation.OutputFormat;

import java.time.LocalDateTime;
import java.util.UUID;

public record TemplateResponse(
        UUID id,
        String name,
        String description,
        OutputFormat outputFormat,
        String sectionSchema,
        String systemPrompt,
        String templatePath,
        boolean isPublic,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
