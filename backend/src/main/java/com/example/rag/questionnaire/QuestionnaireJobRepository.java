package com.example.rag.questionnaire;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuestionnaireJobRepository extends JpaRepository<QuestionnaireJob, UUID> {

    List<QuestionnaireJob> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("SELECT j FROM QuestionnaireJob j JOIN FETCH j.user WHERE j.id = :id")
    Optional<QuestionnaireJob> findByIdWithUser(UUID id);
}
