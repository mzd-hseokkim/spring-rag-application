package kr.co.mz.ragservice.questionnaire.dto;

import kr.co.mz.ragservice.questionnaire.QuestionnaireStatus;

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
