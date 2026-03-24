package com.example.rag.questionnaire.dto;

import com.example.rag.questionnaire.QuestionnaireStatus;

public record QuestionnaireProgressEvent(
        String eventType,
        QuestionnaireStatus status,
        String message,
        Integer currentPersona,
        Integer totalPersonas,
        String personaName,
        String downloadUrl
) {
    public static QuestionnaireProgressEvent status(QuestionnaireStatus status, String message) {
        return new QuestionnaireProgressEvent("status", status, message, null, null, null, null);
    }

    public static QuestionnaireProgressEvent progress(int current, int total, String personaName) {
        return new QuestionnaireProgressEvent("progress", null, null, current, total, personaName, null);
    }

    public static QuestionnaireProgressEvent complete(String downloadUrl) {
        return new QuestionnaireProgressEvent("complete", QuestionnaireStatus.COMPLETE, null, null, null, null, downloadUrl);
    }

    public static QuestionnaireProgressEvent error(String message) {
        return new QuestionnaireProgressEvent("error", QuestionnaireStatus.FAILED, message, null, null, null, null);
    }
}
