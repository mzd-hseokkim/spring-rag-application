package com.example.rag.questionnaire.dto;

import com.example.rag.questionnaire.QuestionnaireStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record QuestionnaireResponse(
        UUID id,
        String title,
        QuestionnaireStatus status,
        String userInput,
        int currentPersona,
        int totalPersonas,
        String outputFilePath,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
