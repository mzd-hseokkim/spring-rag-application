package kr.co.mz.ragservice.auth;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AppUser() {}

    public AppUser(String email, String password, String name) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.role = UserRole.USER;
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
    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public String getName() { return name; }
    public UserRole getRole() { return role; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setName(String name) { this.name = name; }
    public void setPassword(String password) { this.password = password; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public void setRole(UserRole role) { this.role = role; }
}
