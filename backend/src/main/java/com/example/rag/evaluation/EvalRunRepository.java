package com.example.rag.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface EvalRunRepository extends JpaRepository<EvalRunEntity, UUID> {

    List<EvalRunEntity> findAllByOrderByCreatedAtDesc();

    @Query("SELECT r FROM EvalRunEntity r WHERE r.status IN ('RUNNING', 'GENERATING') " +
            "AND (r.leasedUntil IS NULL OR r.leasedUntil < :now)")
    List<EvalRunEntity> findStaleRuns(LocalDateTime now);
}
