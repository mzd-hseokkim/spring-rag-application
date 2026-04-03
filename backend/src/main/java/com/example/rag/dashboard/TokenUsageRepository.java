package com.example.rag.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface TokenUsageRepository extends JpaRepository<TokenUsageEntity, UUID> {

    @Query("SELECT DATE(t.createdAt) as day, SUM(t.inputTokens), SUM(t.outputTokens) " +
            "FROM TokenUsageEntity t WHERE t.createdAt >= :after " +
            "GROUP BY DATE(t.createdAt) ORDER BY day")
    List<Object[]> sumByDay(LocalDateTime after);

    @Query("SELECT u.email, u.name, SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t) " +
            "FROM TokenUsageEntity t JOIN com.example.rag.auth.AppUser u ON t.userId = u.id " +
            "WHERE t.createdAt >= :after " +
            "GROUP BY u.id, u.email, u.name ORDER BY SUM(t.outputTokens) DESC")
    List<Object[]> sumByUser(LocalDateTime after);

    @Query("SELECT t.modelName, t.purpose, SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t) " +
            "FROM TokenUsageEntity t WHERE t.createdAt >= :after " +
            "GROUP BY t.modelName, t.purpose ORDER BY SUM(t.outputTokens) DESC")
    List<Object[]> sumByModel(LocalDateTime after);

    @Query("SELECT t.purpose, SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t) " +
            "FROM TokenUsageEntity t WHERE t.createdAt >= :after " +
            "GROUP BY t.purpose ORDER BY SUM(t.outputTokens) DESC")
    List<Object[]> sumByPurpose(LocalDateTime after);

    @Query("SELECT DATE(t.createdAt) as day, SUM(t.inputTokens), SUM(t.outputTokens) " +
            "FROM TokenUsageEntity t WHERE t.createdAt >= :after AND t.purpose = :purpose " +
            "GROUP BY DATE(t.createdAt) ORDER BY day")
    List<Object[]> sumByDayAndPurpose(LocalDateTime after, String purpose);

    @Query("SELECT u.email, u.name, SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t) " +
            "FROM TokenUsageEntity t JOIN com.example.rag.auth.AppUser u ON t.userId = u.id " +
            "WHERE t.createdAt >= :after AND t.purpose = :purpose " +
            "GROUP BY u.id, u.email, u.name ORDER BY SUM(t.outputTokens) DESC")
    List<Object[]> sumByUserAndPurpose(LocalDateTime after, String purpose);

    @Query("SELECT t.modelName, t.purpose, SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t) " +
            "FROM TokenUsageEntity t WHERE t.createdAt >= :after AND t.purpose = :purpose " +
            "GROUP BY t.modelName, t.purpose ORDER BY SUM(t.outputTokens) DESC")
    List<Object[]> sumByModelAndPurpose(LocalDateTime after, String purpose);

    @Query("SELECT u.email, u.name, t.modelName, SUM(t.inputTokens), SUM(t.outputTokens) " +
            "FROM TokenUsageEntity t JOIN com.example.rag.auth.AppUser u ON t.userId = u.id " +
            "WHERE t.createdAt >= :after " +
            "GROUP BY u.id, u.email, u.name, t.modelName ORDER BY u.email")
    List<Object[]> sumByUserAndModel(LocalDateTime after);
}
