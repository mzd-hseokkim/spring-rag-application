package kr.co.mz.ragservice.questionnaire.persona;

import kr.co.mz.ragservice.auth.AppUser;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "persona")
public class Persona {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 200)
    private String role;

    @Column(name = "focus_areas", length = 500)
    private String focusAreas;

    @Column(columnDefinition = "text")
    private String prompt;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Persona() {}

    public Persona(String name, String role, String focusAreas, String prompt, AppUser user) {
        this.name = name;
        this.role = role;
        this.focusAreas = focusAreas;
        this.prompt = prompt;
        this.user = user;
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
    public String getRole() { return role; }
    public String getFocusAreas() { return focusAreas; }
    public String getPrompt() { return prompt; }
    public boolean isDefault() { return isDefault; }
    public AppUser getUser() { return user; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setRole(String role) { this.role = role; }
    public void setFocusAreas(String focusAreas) { this.focusAreas = focusAreas; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
}
