package com.example.rag.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Async
    public void log(UUID userId, String userEmail, String action, String resource,
                    String resourceId, Map<String, Object> detail, String ipAddress) {
        try {
            repository.save(new AuditLog(userId, userEmail, action, resource, resourceId, detail, ipAddress));
        } catch (Exception e) {
            log.warn("Failed to save audit log: action={}, userId={}", action, userId, e);
        }
    }

    public void log(UUID userId, String userEmail, String action, String resource, String resourceId) {
        log(userId, userEmail, action, resource, resourceId, null, null);
    }

    public void log(UUID userId, String userEmail, String action) {
        log(userId, userEmail, action, null, null, null, null);
    }

    public Page<AuditLogDto> search(String action, UUID userId,
                                     LocalDateTime from, LocalDateTime to, Pageable pageable) {
        return repository.search(action, userId, from, to, pageable).map(AuditLogDto::from);
    }

    public Page<AuditLogDto> findAll(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable).map(AuditLogDto::from);
    }

    public record AuditLogDto(UUID id, UUID userId, String userEmail, String action,
                               String resource, String resourceId, Map<String, Object> detail,
                               String ipAddress, LocalDateTime createdAt) {
        static AuditLogDto from(AuditLog a) {
            return new AuditLogDto(a.getId(), a.getUserId(), a.getUserEmail(), a.getAction(),
                    a.getResource(), a.getResourceId(), a.getDetail(),
                    a.getIpAddress(), a.getCreatedAt());
        }
    }
}
