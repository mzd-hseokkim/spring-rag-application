package com.example.rag.generation.dto;

import com.example.rag.generation.GenerationStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record GenerationResponse(
        UUID id,
        GenerationStatus status,
        UUID templateId,
        String templateName,
        String title,
        String userInput,
        int currentSection,
        int totalSections,
        int currentStep,
        String stepStatus,
        String outline,
        String requirementMapping,
        String generatedSections,
        boolean includeWebSearch,
        String outputFilePath,
        String errorMessage,
        List<DocItem> customerDocuments,
        List<DocItem> referenceDocuments,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public record DocItem(UUID id, String filename, int chunkCount) {}
}
