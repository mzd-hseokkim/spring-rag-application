package com.example.rag.generation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {

    List<GenerationJob> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<GenerationJob> findByStatus(GenerationStatus status);

    @Query("SELECT j FROM GenerationJob j JOIN FETCH j.template JOIN FETCH j.user WHERE j.id = :id")
    Optional<GenerationJob> findByIdWithTemplate(UUID id);
}
