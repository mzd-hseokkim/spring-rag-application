package com.example.rag.document;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "document_chunk")
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // embedding, content_tsv는 Native Query로 직접 관리

    protected DocumentChunk() {}

    public DocumentChunk(Document document, String content, int chunkIndex) {
        this.document = document;
        this.content = content;
        this.chunkIndex = chunkIndex;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public Document getDocument() { return document; }
    public String getContent() { return content; }
    public int getChunkIndex() { return chunkIndex; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
