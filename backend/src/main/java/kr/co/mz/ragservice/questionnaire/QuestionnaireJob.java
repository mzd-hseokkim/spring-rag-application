package kr.co.mz.ragservice.questionnaire;

import kr.co.mz.ragservice.auth.AppUser;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "questionnaire_job")
public class QuestionnaireJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 300)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuestionnaireStatus status;

    @Column(name = "user_input", columnDefinition = "text")
    private String userInput;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generated_qna", columnDefinition = "jsonb")
    private String generatedQna;

    @Column(name = "output_file_path", length = 500)
    private String outputFilePath;

    @Column(name = "current_persona", nullable = false)
    private int currentPersona;

    @Column(name = "total_personas", nullable = false)
    private int totalPersonas;

    @Column(name = "document_analysis", columnDefinition = "text")
    private String documentAnalysis;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected QuestionnaireJob() {}

    public QuestionnaireJob(String userInput, AppUser user) {
        this.userInput = userInput;
        this.user = user;
        this.status = QuestionnaireStatus.ANALYZING;
        this.currentPersona = 0;
        this.totalPersonas = 0;
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
    public String getTitle() { return title; }
    public QuestionnaireStatus getStatus() { return status; }
    public String getUserInput() { return userInput; }
    public String getGeneratedQna() { return generatedQna; }
    public String getOutputFilePath() { return outputFilePath; }
    public int getCurrentPersona() { return currentPersona; }
    public int getTotalPersonas() { return totalPersonas; }
    public String getDocumentAnalysis() { return documentAnalysis; }
    public String getErrorMessage() { return errorMessage; }
    public AppUser getUser() { return user; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setTitle(String title) { this.title = title; }
    public void setStatus(QuestionnaireStatus status) { this.status = status; }
    public void setGeneratedQna(String generatedQna) { this.generatedQna = generatedQna; }
    public void setOutputFilePath(String outputFilePath) { this.outputFilePath = outputFilePath; }
    public void setCurrentPersona(int currentPersona) { this.currentPersona = currentPersona; }
    public void setTotalPersonas(int totalPersonas) { this.totalPersonas = totalPersonas; }
    public void setDocumentAnalysis(String documentAnalysis) { this.documentAnalysis = documentAnalysis; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
