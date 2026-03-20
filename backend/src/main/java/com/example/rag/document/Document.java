package com.example.rag.document;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 500)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Document() {}

    public Document(String filename, String contentType, long fileSize) {
        this.filename = filename;
        this.contentType = contentType;
        this.fileSize = fileSize;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public String getFilename() { return filename; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
    public DocumentStatus getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public int getChunkCount() { return chunkCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void markProcessing() {
        this.status = DocumentStatus.PROCESSING;
    }

    public void markCompleted(int chunkCount) {
        this.status = DocumentStatus.COMPLETED;
        this.chunkCount = chunkCount;
    }

    public void markFailed(String errorMessage) {
        this.status = DocumentStatus.FAILED;
        this.errorMessage = errorMessage;
    }
}
