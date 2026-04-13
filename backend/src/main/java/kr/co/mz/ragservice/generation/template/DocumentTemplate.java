package kr.co.mz.ragservice.generation.template;

import kr.co.mz.ragservice.auth.AppUser;
import kr.co.mz.ragservice.generation.OutputFormat;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "document_template")
public class DocumentTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "output_format", nullable = false, length = 20)
    private OutputFormat outputFormat;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "section_schema", columnDefinition = "jsonb")
    private String sectionSchema;

    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "template_path", length = 500)
    private String templatePath;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected DocumentTemplate() {}

    public DocumentTemplate(String name, String description, OutputFormat outputFormat,
                            String sectionSchema, String systemPrompt) {
        this.name = name;
        this.description = description;
        this.outputFormat = outputFormat;
        this.sectionSchema = sectionSchema;
        this.systemPrompt = systemPrompt;
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
    public String getName() { return name; }
    public String getDescription() { return description; }
    public OutputFormat getOutputFormat() { return outputFormat; }
    public String getSectionSchema() { return sectionSchema; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getTemplatePath() { return templatePath; }
    public boolean isPublic() { return isPublic; }
    public AppUser getUser() { return user; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setOutputFormat(OutputFormat outputFormat) { this.outputFormat = outputFormat; }
    public void setSectionSchema(String sectionSchema) { this.sectionSchema = sectionSchema; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public void setTemplatePath(String templatePath) { this.templatePath = templatePath; }
    public void setPublic(boolean isPublic) { this.isPublic = isPublic; }
    public void setUser(AppUser user) { this.user = user; }
}
