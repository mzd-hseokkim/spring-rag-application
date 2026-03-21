package com.example.rag.document.tag;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DocumentTagRepository extends JpaRepository<DocumentTag, UUID> {
}
