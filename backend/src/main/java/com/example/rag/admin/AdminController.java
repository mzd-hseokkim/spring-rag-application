package com.example.rag.admin;

import com.example.rag.auth.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
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
        UUID currentUserId = UUID.fromString(auth.getName());
        UserRole role = UserRole.valueOf(body.get("role"));
        return adminService.updateRole(id, role, currentUserId);
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable UUID id, Authentication auth) {
        UUID currentUserId = UUID.fromString(auth.getName());
        adminService.deleteUser(id, currentUserId);
    }

    // --- 문서 관리 ---

    @GetMapping("/documents")
    public Page<AdminService.AdminDocumentDto> listDocuments(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return adminService.listDocuments(pageable);
    }

    @PatchMapping("/documents/{id}/public")
    public AdminService.AdminDocumentDto updatePublic(@PathVariable UUID id,
                                                       @RequestBody Map<String, Boolean> body) {
        return adminService.updatePublic(id, body.get("isPublic"));
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable UUID id) {
        adminService.deleteDocument(id);
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
    public void deleteConversation(@PathVariable UUID id) {
        adminService.deleteConversation(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleError(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }
}
