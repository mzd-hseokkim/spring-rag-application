package kr.co.mz.ragservice.document;

import java.time.LocalDateTime;
import java.util.List;
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
        List<TagInfo> tags,
        List<CollectionInfo> collections,
        LocalDateTime createdAt
) {
    public static DocumentResponse from(Document document) {
        List<TagInfo> tags = document.getTags().stream()
                .map(t -> new TagInfo(t.getId(), t.getName()))
                .toList();
        List<CollectionInfo> collections = document.getCollections().stream()
                .map(c -> new CollectionInfo(c.getId(), c.getName()))
                .toList();
        return new DocumentResponse(
                document.getId(),
                document.getFilename(),
                document.getContentType(),
                document.getFileSize(),
                document.getStatus(),
                document.getErrorMessage(),
                document.getChunkCount(),
                document.isPublic(),
                tags,
                collections,
                document.getCreatedAt()
        );
    }

    public record TagInfo(UUID id, String name) {}
    public record CollectionInfo(UUID id, String name) {}
}
