package com.example.rag.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(UUID documentId);

    List<DocumentChunk> findByDocumentIdAndChunkIndexBetweenOrderByChunkIndex(
            UUID documentId, int fromIndex, int toIndex);

    @Modifying
    @Query(value = "UPDATE document_chunk SET embedding = cast(:embedding AS vector), " +
            "content_tsv = to_tsvector('simple', :content) WHERE id = :id", nativeQuery = true)
    void updateEmbeddingAndTsvector(@Param("id") UUID id,
                                    @Param("embedding") String embedding,
                                    @Param("content") String content);

    @Modifying
    @Query(value = "UPDATE document_chunk SET embedding = cast(:embedding AS vector), " +
            "content_tsv = to_tsvector('simple', :content), " +
            "metadata = cast(:metadata AS jsonb) WHERE id = :id", nativeQuery = true)
    void updateEmbeddingTsvectorAndMetadata(@Param("id") UUID id,
                                             @Param("embedding") String embedding,
                                             @Param("content") String content,
                                             @Param("metadata") String metadata);

    @Modifying
    @Query("DELETE FROM DocumentChunk c WHERE c.document.id = :documentId")
    void deleteByDocumentId(@Param("documentId") UUID documentId);
}
