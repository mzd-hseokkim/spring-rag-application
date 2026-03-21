package com.example.rag.dashboard;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PipelineTraceRepository extends JpaRepository<PipelineTraceEntity, UUID> {

    Page<PipelineTraceEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByCreatedAtAfter(LocalDateTime after);

    @Query("SELECT DATE(t.createdAt) as day, COUNT(t) FROM PipelineTraceEntity t " +
            "WHERE t.createdAt >= :after GROUP BY DATE(t.createdAt) ORDER BY day")
    List<Object[]> countByDay(LocalDateTime after);

    @Query("SELECT t.agentAction, COUNT(t) FROM PipelineTraceEntity t " +
            "WHERE t.createdAt >= :after GROUP BY t.agentAction")
    List<Object[]> countByAgentAction(LocalDateTime after);

    @Query("SELECT AVG(t.totalLatency) FROM PipelineTraceEntity t WHERE t.createdAt >= :after")
    Double avgLatency(LocalDateTime after);
}
