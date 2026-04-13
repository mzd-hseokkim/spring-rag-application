package kr.co.mz.ragservice.dashboard;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "evaluation_result")
public class EvaluationResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trace_id", length = 20)
    private String traceId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String query;

    private Double faithfulness;
    private Double relevance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected EvaluationResultEntity() {}

    public EvaluationResultEntity(String traceId, UUID userId, String query,
                                   Double faithfulness, Double relevance) {
        this.traceId = traceId;
        this.userId = userId;
        this.query = query;
        this.faithfulness = faithfulness;
        this.relevance = relevance;
    }

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }

    public UUID getId() { return id; }
    public String getTraceId() { return traceId; }
    public UUID getUserId() { return userId; }
    public String getQuery() { return query; }
    public Double getFaithfulness() { return faithfulness; }
    public Double getRelevance() { return relevance; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
