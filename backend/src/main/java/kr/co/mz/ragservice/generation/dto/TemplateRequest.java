package kr.co.mz.ragservice.generation.dto;

import kr.co.mz.ragservice.generation.OutputFormat;

public record TemplateRequest(
        String name,
        String description,
        OutputFormat outputFormat,
        String sectionSchema,
        String systemPrompt,
        String templatePath,
        boolean isPublic
) {}
