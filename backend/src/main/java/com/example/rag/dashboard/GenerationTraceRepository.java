package com.example.rag.dashboard;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GenerationTraceRepository extends JpaRepository<GenerationTraceEntity, UUID> {

    List<GenerationTraceEntity> findByJobIdOrderByStartedAtAsc(UUID jobId);

    Page<GenerationTraceEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
}
