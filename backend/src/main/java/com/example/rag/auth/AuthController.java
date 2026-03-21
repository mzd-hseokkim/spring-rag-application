package com.example.rag.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.UserDto register(@RequestBody RegisterRequest request) {
        return authService.register(request.email(), request.password(), request.name());
    }

    @PostMapping("/login")
    public AuthService.AuthResponse login(@RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
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
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleError(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }

    record RegisterRequest(String email, String password, String name) {}
    record LoginRequest(String email, String password) {}
    record RefreshRequest(String refreshToken) {}
}
