package com.example.rag.document;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByStatus(DocumentStatus status);

    List<Document> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT d FROM Document d LEFT JOIN FETCH d.user",
            countQuery = "SELECT COUNT(d) FROM Document d")
    org.springframework.data.domain.Page<Document> findAllWithUser(org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            "SELECT d FROM Document d WHERE d.user.id = :userId OR d.isPublic = true ORDER BY d.createdAt DESC")
    List<Document> findByUserIdOrIsPublicTrue(UUID userId);

    @org.springframework.data.jpa.repository.Query(
            "SELECT d FROM Document d WHERE d.status = :status AND (d.user.id = :userId OR d.isPublic = true)")
    List<Document> findSearchableDocuments(DocumentStatus status, UUID userId);

    @org.springframework.data.jpa.repository.Query(
            "SELECT d FROM Document d WHERE d.status = :status AND d.user.id = :userId")
    List<Document> findByStatusAndUserId(DocumentStatus status, UUID userId);
}
