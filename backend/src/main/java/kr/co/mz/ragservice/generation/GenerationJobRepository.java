package kr.co.mz.ragservice.generation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {

    List<GenerationJob> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<GenerationJob> findByStatus(GenerationStatus status);

    @Query("SELECT j FROM GenerationJob j JOIN FETCH j.template JOIN FETCH j.user WHERE j.id = :id")
    Optional<GenerationJob> findByIdWithTemplate(UUID id);

    long countByCreatedAtAfter(LocalDateTime after);

    long countByStatus(GenerationStatus status);

    @Query("SELECT DATE(j.createdAt) as day, COUNT(j) FROM GenerationJob j " +
            "WHERE j.createdAt >= :after GROUP BY DATE(j.createdAt) ORDER BY day")
    List<Object[]> countByDay(LocalDateTime after);

    @Query("SELECT j FROM GenerationJob j JOIN FETCH j.user ORDER BY j.createdAt DESC")
    Page<GenerationJob> findAllWithUser(Pageable pageable);

    @Query("SELECT j FROM GenerationJob j JOIN FETCH j.user WHERE j.status = :status ORDER BY j.createdAt DESC")
    Page<GenerationJob> findAllWithUserByStatus(GenerationStatus status, Pageable pageable);
}
