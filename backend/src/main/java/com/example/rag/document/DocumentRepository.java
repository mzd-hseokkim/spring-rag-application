package com.example.rag.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByStatus(DocumentStatus status);

    List<Document> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Document> findByCollectionsId(UUID collectionId);

    @Query(value = "SELECT DISTINCT d FROM Document d LEFT JOIN FETCH d.user LEFT JOIN FETCH d.tags",
            countQuery = "SELECT COUNT(d) FROM Document d")
    org.springframework.data.domain.Page<Document> findAllWithUser(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT DISTINCT d FROM Document d LEFT JOIN FETCH d.tags LEFT JOIN FETCH d.collections WHERE d.user.id = :userId OR d.isPublic = true ORDER BY d.createdAt DESC")
    List<Document> findByUserIdOrIsPublicTrue(UUID userId);

    @Query("SELECT DISTINCT d FROM Document d LEFT JOIN FETCH d.tags LEFT JOIN FETCH d.collections WHERE d.status = :status AND (d.user.id = :userId OR d.isPublic = true)")
    List<Document> findSearchableDocuments(DocumentStatus status, UUID userId);

    @Query("SELECT DISTINCT d FROM Document d LEFT JOIN FETCH d.tags LEFT JOIN FETCH d.collections WHERE d.status = :status AND d.user.id = :userId")
    List<Document> findByStatusAndUserId(DocumentStatus status, UUID userId);

    @Query("SELECT d FROM Document d LEFT JOIN FETCH d.tags LEFT JOIN FETCH d.collections WHERE d.id = :id")
    Optional<Document> findByIdWithTags(UUID id);
}
