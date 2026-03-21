package com.example.rag.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface EvaluationResultRepository extends JpaRepository<EvaluationResultEntity, UUID> {
}
