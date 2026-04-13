package kr.co.mz.ragservice.questionnaire.dto;

import java.util.List;
import java.util.UUID;

public record QuestionnaireRequest(
        List<UUID> customerDocumentIds,
        List<UUID> proposalDocumentIds,
        List<UUID> referenceDocumentIds,
        List<UUID> personaIds,
        String userInput,
        int questionCount,
        boolean includeWebSearch,
        String analysisMode
) {
    public QuestionnaireRequest {
        if (questionCount <= 0) questionCount = 7;
        if (questionCount > 20) questionCount = 20;
        if (analysisMode == null || analysisMode.isBlank()) analysisMode = "RAG";
    }
}
