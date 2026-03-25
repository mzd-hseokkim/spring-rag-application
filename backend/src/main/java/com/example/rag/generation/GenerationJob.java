package com.example.rag.generation;

import com.example.rag.auth.AppUser;
import com.example.rag.generation.template.DocumentTemplate;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "generation_job")
public class GenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GenerationStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private DocumentTemplate template;

    @Column(length = 300)
    private String title;

    @Column(name = "user_input", columnDefinition = "text", nullable = false)
    private String userInput;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String outline;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generated_sections", columnDefinition = "jsonb")
    private String generatedSections;

    @Column(name = "output_file_path", length = 500)
    private String outputFilePath;

    @Column(name = "current_section", nullable = false)
    private int currentSection;

    @Column(name = "total_sections", nullable = false)
    private int totalSections;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Column(name = "step_status", length = 20, nullable = false)
    private String stepStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requirement_mapping", columnDefinition = "jsonb")
    private String requirementMapping;

    @Column(name = "include_web_search", nullable = false)
    private boolean includeWebSearch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected GenerationJob() {}

    public GenerationJob(DocumentTemplate template, String userInput, AppUser user) {
        this.template = template;
        this.userInput = userInput;
        this.user = user;
        this.status = GenerationStatus.DRAFT;
        this.currentSection = 0;
        this.totalSections = 0;
        this.currentStep = 1;
        this.stepStatus = "IDLE";
    }

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

    public UUID getId() { return id; }
    public GenerationStatus getStatus() { return status; }
    public DocumentTemplate getTemplate() { return template; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getUserInput() { return userInput; }
    public String getOutline() { return outline; }
    public String getGeneratedSections() { return generatedSections; }
    public String getOutputFilePath() { return outputFilePath; }
    public int getCurrentSection() { return currentSection; }
    public int getTotalSections() { return totalSections; }
    public String getErrorMessage() { return errorMessage; }
    public AppUser getUser() { return user; }
    public UUID getConversationId() { return conversationId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setStatus(GenerationStatus status) { this.status = status; }
    public void setOutline(String outline) { this.outline = outline; }
    public void setGeneratedSections(String generatedSections) { this.generatedSections = generatedSections; }
    public void setOutputFilePath(String outputFilePath) { this.outputFilePath = outputFilePath; }
    public void setCurrentSection(int currentSection) { this.currentSection = currentSection; }
    public void setTotalSections(int totalSections) { this.totalSections = totalSections; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }

    public int getCurrentStep() { return currentStep; }
    public void setCurrentStep(int currentStep) { this.currentStep = currentStep; }
    public String getStepStatus() { return stepStatus; }
    public void setStepStatus(String stepStatus) { this.stepStatus = stepStatus; }
    public String getRequirementMapping() { return requirementMapping; }
    public void setRequirementMapping(String requirementMapping) { this.requirementMapping = requirementMapping; }

    public boolean isIncludeWebSearch() { return includeWebSearch; }
    public void setIncludeWebSearch(boolean includeWebSearch) { this.includeWebSearch = includeWebSearch; }
}
