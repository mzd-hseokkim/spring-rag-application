package com.example.rag.evaluation;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "eval_run")
public class EvalRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "total_questions", nullable = false)
    private int totalQuestions = 0;

    @Column(name = "completed_questions", nullable = false)
    private int completedQuestions = 0;

    @Column(name = "avg_faithfulness")
    private Double avgFaithfulness;

    @Column(name = "avg_relevance")
    private Double avgRelevance;

    @Column(name = "avg_correctness")
    private Double avgCorrectness;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "leased_until")
    private LocalDateTime leasedUntil;

    @Column(name = "leased_by", length = 100)
    private String leasedBy;

    protected EvalRunEntity() {}

    public EvalRunEntity(String name) {
        this.name = name;
    }

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public int getTotalQuestions() { return totalQuestions; }
    public int getCompletedQuestions() { return completedQuestions; }
    public Double getAvgFaithfulness() { return avgFaithfulness; }
    public Double getAvgRelevance() { return avgRelevance; }
    public Double getAvgCorrectness() { return avgCorrectness; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setTotalQuestions(int totalQuestions) { this.totalQuestions = totalQuestions; }
    public void setCompletedQuestions(int completedQuestions) { this.completedQuestions = completedQuestions; }
    public void setAvgFaithfulness(Double avgFaithfulness) { this.avgFaithfulness = avgFaithfulness; }
    public void setAvgRelevance(Double avgRelevance) { this.avgRelevance = avgRelevance; }
    public void setAvgCorrectness(Double avgCorrectness) { this.avgCorrectness = avgCorrectness; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public LocalDateTime getLeasedUntil() { return leasedUntil; }
    public void setLeasedUntil(LocalDateTime leasedUntil) { this.leasedUntil = leasedUntil; }
    public String getLeasedBy() { return leasedBy; }
    public void setLeasedBy(String leasedBy) { this.leasedBy = leasedBy; }
}
