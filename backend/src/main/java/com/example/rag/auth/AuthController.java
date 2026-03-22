package com.example.rag.auth;

import com.example.rag.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuditService auditService;

    public AuthController(AuthService authService, AuditService auditService) {
        this.authService = authService;
        this.auditService = auditService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.UserDto register(@RequestBody RegisterRequest request, HttpServletRequest httpReq) {
        AuthService.UserDto user = authService.register(request.email(), request.password(), request.name());
        auditService.log(user.id(), request.email(), "REGISTER", "USER", user.id().toString(),
                null, httpReq.getRemoteAddr());
        return user;
    }

    @PostMapping("/login")
    public AuthService.AuthResponse login(@RequestBody LoginRequest request, HttpServletRequest httpReq) {
        try {
            AuthService.AuthResponse response = authService.login(request.email(), request.password());
            auditService.log(response.user().id(), request.email(), "LOGIN_SUCCESS", "USER",
                    response.user().id().toString(), null, httpReq.getRemoteAddr());
            return response;
        } catch (IllegalArgumentException e) {
            auditService.log(null, request.email(), "LOGIN_FAILED", "USER", null,
                    Map.of("reason", e.getMessage()), httpReq.getRemoteAddr());
            throw e;
        }
    }

    @PostMapping("/refresh")
    public AuthService.AuthResponse refresh(@RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @GetMapping("/me")
    public AuthService.UserDto me(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return authService.getUser(userId);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        authService.logout(userId);
        auditService.log(userId, null, "LOGOUT");
    }

    @PutMapping("/profile")
    public AuthService.UserDto updateProfile(@RequestBody ProfileRequest request, Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return authService.updateProfile(userId, request.name(), request.avatarUrl());
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestBody PasswordChangeRequest request, Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        authService.changePassword(userId, request.currentPassword(), request.newPassword());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleError(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }

    record RegisterRequest(String email, String password, String name) {}
    record LoginRequest(String email, String password) {}
    record RefreshRequest(String refreshToken) {}
    record ProfileRequest(String name, String avatarUrl) {}
    record PasswordChangeRequest(String currentPassword, String newPassword) {}
}
