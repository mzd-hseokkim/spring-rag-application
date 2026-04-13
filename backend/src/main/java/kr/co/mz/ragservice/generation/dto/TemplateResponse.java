package kr.co.mz.ragservice.generation.dto;

import kr.co.mz.ragservice.generation.OutputFormat;

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
