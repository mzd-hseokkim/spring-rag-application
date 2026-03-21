package com.example.rag.document;

import java.time.LocalDateTime;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String filename,
        String contentType,
        long fileSize,
        DocumentStatus status,
        String errorMessage,
        int chunkCount,
        boolean isPublic,
        LocalDateTime createdAt
) {
    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getFilename(),
                document.getContentType(),
                document.getFileSize(),
                document.getStatus(),
                document.getErrorMessage(),
                document.getChunkCount(),
                document.isPublic(),
                document.getCreatedAt()
        );
    }
}
