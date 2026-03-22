package com.example.rag.admin;

import com.example.rag.audit.AuditService;
import com.example.rag.auth.UserRole;
import com.example.rag.document.pipeline.ReindexService;
import com.example.rag.settings.ChunkingSettings;
import com.example.rag.settings.EmbeddingSettings;
import com.example.rag.settings.SettingsService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final String KEY_IS_PUBLIC = "isPublic";
    private static final String TARGET_DOCUMENT = "DOCUMENT";
    private static final String KEY_MESSAGE = "message";

    private final AdminService adminService;
    private final SettingsService settingsService;
    private final ReindexService reindexService;
    private final AuditService auditService;

    public AdminController(AdminService adminService,
                           SettingsService settingsService,
                           ReindexService reindexService,
                           AuditService auditService) {
        this.adminService = adminService;
        this.settingsService = settingsService;
        this.reindexService = reindexService;
        this.auditService = auditService;
    }

    private UUID currentUserId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }

    // --- 사용자 관리 ---

    @GetMapping("/users")
    public Page<AdminService.AdminUserDto> listUsers(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return adminService.listUsers(q, pageable);
    }

    @PatchMapping("/users/{id}/role")
    public AdminService.AdminUserDto updateRole(@PathVariable UUID id,
                                                 @RequestBody Map<String, String> body,
                                                 Authentication auth) {
        UUID adminId = currentUserId(auth);
        UserRole role = UserRole.valueOf(body.get("role"));
        AdminService.AdminUserDto result = adminService.updateRole(id, role, adminId);
        auditService.log(adminId, null, "CHANGE_ROLE", "USER", id.toString(),
                Map.of("newRole", role.name()), null);
        return result;
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable UUID id, Authentication auth) {
        UUID adminId = currentUserId(auth);
        adminService.deleteUser(id, adminId);
        auditService.log(adminId, null, "DELETE_USER", "USER", id.toString());
    }

    // --- 문서 관리 ---

    @GetMapping("/documents")
    public Page<AdminService.AdminDocumentDto> listDocuments(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return adminService.listDocuments(pageable);
    }

    @PatchMapping("/documents/{id}/public")
    public AdminService.AdminDocumentDto updatePublic(@PathVariable UUID id,
                                                       @RequestBody Map<String, Boolean> body,
                                                       Authentication auth) {
        AdminService.AdminDocumentDto result = adminService.updatePublic(id, body.get(KEY_IS_PUBLIC));
        auditService.log(currentUserId(auth), null, "TOGGLE_PUBLIC", TARGET_DOCUMENT, id.toString(),
                Map.of(KEY_IS_PUBLIC, body.get(KEY_IS_PUBLIC)), null);
        return result;
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable UUID id, Authentication auth) {
        adminService.deleteDocument(id);
        auditService.log(currentUserId(auth), null, "DELETE_DOCUMENT", TARGET_DOCUMENT, id.toString());
    }

    // --- 대화 관리 ---

    @GetMapping("/conversations")
    public Page<AdminService.AdminConversationDto> listConversations(
            @PageableDefault(size = 20, sort = "updatedAt") Pageable pageable) {
        return adminService.listConversations(pageable);
    }

    @GetMapping("/conversations/{id}")
    public AdminService.AdminConversationDetailDto getConversationDetail(@PathVariable UUID id) {
        return adminService.getConversationDetail(id);
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(@PathVariable UUID id, Authentication auth) {
        adminService.deleteConversation(id);
        auditService.log(currentUserId(auth), null, "DELETE_CONVERSATION", "CONVERSATION", id.toString());
    }

    // --- 재인덱싱 ---

    @PostMapping("/documents/{id}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> reindexDocument(@PathVariable UUID id, Authentication auth) {
        reindexService.reindexDocument(id);
        auditService.log(currentUserId(auth), null, "REINDEX", TARGET_DOCUMENT, id.toString());
        return Map.of(KEY_MESSAGE, "재인덱싱이 시작되었습니다.");
    }

    @PostMapping("/documents/reindex-all")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> reindexAll(Authentication auth) {
        reindexService.reindexAll();
        auditService.log(currentUserId(auth), null, "REINDEX_ALL", TARGET_DOCUMENT, null);
        return Map.of(KEY_MESSAGE, "전체 재인덱싱이 시작되었습니다.");
    }

    // --- 설정 ---

    @GetMapping("/settings/chunking")
    public ChunkingSettings getChunkingSettings() {
        return settingsService.getChunkingSettings();
    }

    @PutMapping("/settings/chunking")
    public ChunkingSettings updateChunkingSettings(@RequestBody ChunkingSettings settings, Authentication auth) {
        ChunkingSettings result = settingsService.updateChunkingSettings(settings);
        auditService.log(currentUserId(auth), null, "UPDATE_SETTINGS", "CHUNKING", null);
        return result;
    }

    @GetMapping("/settings/embedding")
    public EmbeddingSettings getEmbeddingSettings() {
        return settingsService.getEmbeddingSettings();
    }

    @PutMapping("/settings/embedding")
    public EmbeddingSettings updateEmbeddingSettings(@RequestBody EmbeddingSettings settings, Authentication auth) {
        EmbeddingSettings result = settingsService.updateEmbeddingSettings(settings);
        auditService.log(currentUserId(auth), null, "UPDATE_SETTINGS", "EMBEDDING", null);
        return result;
    }

    // --- 감사 로그 ---

    @GetMapping("/audit-logs")
    public Page<AuditService.AuditLogDto> getAuditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @PageableDefault(size = 30, sort = "createdAt") Pageable pageable) {
        if (action == null && userId == null && from == null && to == null) {
            return auditService.findAll(pageable);
        }
        return auditService.search(action, userId, from, to, pageable);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleError(IllegalArgumentException e) {
        return Map.of(KEY_MESSAGE, e.getMessage());
    }
}
