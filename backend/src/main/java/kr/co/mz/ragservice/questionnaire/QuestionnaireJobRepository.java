package kr.co.mz.ragservice.questionnaire;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuestionnaireJobRepository extends JpaRepository<QuestionnaireJob, UUID> {

    List<QuestionnaireJob> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("SELECT j FROM QuestionnaireJob j JOIN FETCH j.user WHERE j.id = :id")
    Optional<QuestionnaireJob> findByIdWithUser(UUID id);

    long countByCreatedAtAfter(LocalDateTime after);

    long countByStatus(QuestionnaireStatus status);

    @Query("SELECT DATE(j.createdAt) as day, COUNT(j) FROM QuestionnaireJob j " +
            "WHERE j.createdAt >= :after GROUP BY DATE(j.createdAt) ORDER BY day")
    List<Object[]> countByDay(LocalDateTime after);

    @Query("SELECT j FROM QuestionnaireJob j JOIN FETCH j.user ORDER BY j.createdAt DESC")
    Page<QuestionnaireJob> findAllWithUser(Pageable pageable);

    @Query("SELECT j FROM QuestionnaireJob j JOIN FETCH j.user WHERE j.status = :status ORDER BY j.createdAt DESC")
    Page<QuestionnaireJob> findAllWithUserByStatus(QuestionnaireStatus status, Pageable pageable);
}
