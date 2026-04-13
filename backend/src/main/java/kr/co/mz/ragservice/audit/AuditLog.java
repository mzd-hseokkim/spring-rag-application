package kr.co.mz.ragservice.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "user_email")
    private String userEmail;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(length = 50)
    private String resource;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detail;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected AuditLog() {}

    public AuditLog(UUID userId, String userEmail, String action, String resource,
                    String resourceId, Map<String, Object> detail, String ipAddress) {
        this.userId = userId;
        this.userEmail = userEmail;
        this.action = action;
        this.resource = resource;
        this.resourceId = resourceId;
        this.detail = detail;
        this.ipAddress = ipAddress;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getUserEmail() { return userEmail; }
    public String getAction() { return action; }
    public String getResource() { return resource; }
    public String getResourceId() { return resourceId; }
    public Map<String, Object> getDetail() { return detail; }
    public String getIpAddress() { return ipAddress; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
