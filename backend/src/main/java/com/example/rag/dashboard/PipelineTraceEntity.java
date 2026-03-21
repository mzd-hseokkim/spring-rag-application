package com.example.rag.dashboard;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "pipeline_trace")
public class PipelineTraceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trace_id", nullable = false, length = 20)
    private String traceId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String query;

    @Column(name = "agent_action", length = 30)
    private String agentAction;

    @Column(name = "total_latency", nullable = false)
    private int totalLatency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String steps;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected PipelineTraceEntity() {}

    public PipelineTraceEntity(String traceId, String sessionId, UUID userId, String query,
                                String agentAction, int totalLatency, String steps) {
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.query = query;
        this.agentAction = agentAction;
        this.totalLatency = totalLatency;
        this.steps = steps;
    }

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }

    public UUID getId() { return id; }
    public String getTraceId() { return traceId; }
    public String getSessionId() { return sessionId; }
    public UUID getUserId() { return userId; }
    public String getQuery() { return query; }
    public String getAgentAction() { return agentAction; }
    public int getTotalLatency() { return totalLatency; }
    public String getSteps() { return steps; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
