package kr.co.mz.ragservice.settings;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "system_settings")
public class SystemSettings {

    @Id
    @Column(name = "key", length = 100)
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> value;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SystemSettings() {}

    public SystemSettings(String key, Map<String, Object> value) {
        this.key = key;
        this.value = value;
    }

    @PrePersist
    @PreUpdate
    void onSave() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getKey() { return key; }
    public Map<String, Object> getValue() { return value; }
    public void setValue(Map<String, Object> value) { this.value = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
