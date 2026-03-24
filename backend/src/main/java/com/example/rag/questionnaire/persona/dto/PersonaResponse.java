package com.example.rag.questionnaire.persona.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record PersonaResponse(
        UUID id,
        String name,
        String role,
        String focusAreas,
        String prompt,
        boolean isDefault,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
