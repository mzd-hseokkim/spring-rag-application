package kr.co.mz.ragservice.dashboard;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "token_usage")
public class TokenUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "model_name", nullable = false, length = 200)
    private String modelName;

    @Column(nullable = false, length = 30)
    private String purpose;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected TokenUsageEntity() {}

    public TokenUsageEntity(UUID userId, String modelName, String purpose,
                             int inputTokens, int outputTokens, String sessionId) {
        this.userId = userId;
        this.modelName = modelName;
        this.purpose = purpose;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.sessionId = sessionId;
    }

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getModelName() { return modelName; }
    public String getPurpose() { return purpose; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public String getSessionId() { return sessionId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
