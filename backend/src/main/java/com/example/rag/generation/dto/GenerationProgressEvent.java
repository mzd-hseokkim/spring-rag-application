package com.example.rag.generation.dto;

import com.example.rag.generation.GenerationStatus;

public record GenerationProgressEvent(
        String eventType,
        GenerationStatus status,
        String message,
        Integer currentSection,
        Integer totalSections,
        String sectionTitle,
        String downloadUrl
) {
    public static GenerationProgressEvent status(GenerationStatus status, String message) {
        return new GenerationProgressEvent("status", status, message, null, null, null, null);
    }

    public static GenerationProgressEvent progress(int current, int total, String sectionTitle) {
        return new GenerationProgressEvent("progress", null, null, current, total, sectionTitle, null);
    }

    public static GenerationProgressEvent complete(String downloadUrl) {
        return new GenerationProgressEvent("complete", GenerationStatus.COMPLETE, null, null, null, null, downloadUrl);
    }

    public static GenerationProgressEvent error(String message) {
        return new GenerationProgressEvent("error", GenerationStatus.FAILED, message, null, null, null, null);
    }
}
