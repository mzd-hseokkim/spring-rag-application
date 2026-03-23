package com.example.rag.generation.dto;

import com.example.rag.generation.GenerationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record GenerationResponse(
        UUID id,
        GenerationStatus status,
        UUID templateId,
        String templateName,
        int currentSection,
        int totalSections,
        String outputFilePath,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
