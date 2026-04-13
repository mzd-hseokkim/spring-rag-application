package kr.co.mz.ragservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "llm_model")
public class LlmModel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ModelProvider provider;

    @Column(name = "model_id", nullable = false, length = 200)
    private String modelId;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ModelPurpose purpose;

    @Column(name = "is_default", nullable = false)
    private boolean defaultModel = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(name = "api_key_ref", length = 500)
    private String apiKeyRef;

    @Column
    private Double temperature;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LlmModel() {}

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters
    public UUID getId() { return id; }
    public ModelProvider getProvider() { return provider; }
    public String getModelId() { return modelId; }
    public String getDisplayName() { return displayName; }
    public ModelPurpose getPurpose() { return purpose; }
    @JsonProperty("isDefault")
    public boolean isDefaultModel() { return defaultModel; }
    @JsonProperty("isActive")
    public boolean isActive() { return active; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKeyRef() { return apiKeyRef; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // Setters
    public void setProvider(ModelProvider provider) { this.provider = provider; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setPurpose(ModelPurpose purpose) { this.purpose = purpose; }
    @JsonProperty("isDefault")
    public void setDefaultModel(boolean defaultModel) { this.defaultModel = defaultModel; }
    @JsonProperty("isActive")
    public void setActive(boolean active) { this.active = active; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setApiKeyRef(String apiKeyRef) { this.apiKeyRef = apiKeyRef; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
}
