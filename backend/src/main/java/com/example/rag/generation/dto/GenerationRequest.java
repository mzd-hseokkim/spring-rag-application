package com.example.rag.generation.dto;

import java.util.List;
import java.util.UUID;

public record GenerationRequest(
        UUID templateId,
        String userInput,
        UUID conversationId,
        GenerationOptions options
) {
    public record GenerationOptions(
            boolean includeReview,
            List<UUID> tagIds,
            List<UUID> collectionIds,
            List<UUID> documentIds
    ) {}
}
