package com.example.rag.document.collection;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DocumentCollectionRepository extends JpaRepository<DocumentCollection, UUID> {

    List<DocumentCollection> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
