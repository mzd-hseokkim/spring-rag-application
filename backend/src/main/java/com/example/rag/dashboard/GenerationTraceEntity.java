package com.example.rag.dashboard;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "generation_trace")
public class GenerationTraceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "job_type", nullable = false, length = 20)
    private String jobType;

    @Column(name = "step_name", nullable = false, length = 50)
    private String stepName;

    @Column(nullable = false, length = 20)
    private String status = "RUNNING";

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    protected GenerationTraceEntity() {}

    public GenerationTraceEntity(UUID jobId, String jobType, String stepName) {
        this.jobId = jobId;
        this.jobType = jobType;
        this.stepName = stepName;
        this.startedAt = LocalDateTime.now();
    }

    public void complete() {
        this.status = "COMPLETED";
        this.completedAt = LocalDateTime.now();
        this.durationMs = java.time.Duration.between(startedAt, completedAt).toMillis();
    }

    public void fail(String errorMessage) {
        this.status = "FAILED";
        this.completedAt = LocalDateTime.now();
        this.durationMs = java.time.Duration.between(startedAt, completedAt).toMillis();
        this.errorMessage = errorMessage != null && errorMessage.length() > 1000
                ? errorMessage.substring(0, 1000) : errorMessage;
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public String getJobType() { return jobType; }
    public String getStepName() { return stepName; }
    public String getStatus() { return status; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public Long getDurationMs() { return durationMs; }
    public String getErrorMessage() { return errorMessage; }
}
