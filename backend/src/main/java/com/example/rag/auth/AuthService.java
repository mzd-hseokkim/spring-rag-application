package com.example.rag.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AppUserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtTokenProvider jwtTokenProvider,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserDto register(String email, String password, String name) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("이미 등록된 이메일입니다.");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("비밀번호는 8자 이상이어야 합니다.");
        }

        AppUser user = new AppUser(email, passwordEncoder.encode(password), name);
        userRepository.save(user);
        return toDto(user);
    }

    @Transactional
    public AuthResponse login(String email, String password) {
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        return createTokenResponse(user);
    }

    @Transactional
    public AuthResponse refresh(String refreshTokenValue) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 리프레시 토큰입니다."));

        if (refreshToken.isExpired()) {
            refreshTokenRepository.deleteByTokenValue(refreshTokenValue);
            throw new IllegalArgumentException("리프레시 토큰이 만료되었습니다. 다시 로그인해주세요.");
        }

        AppUser user = refreshToken.getUser();
        refreshTokenRepository.deleteByTokenValue(refreshTokenValue);

        return createTokenResponse(user);
    }

    public UserDto getUser(UUID userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        return toDto(user);
    }

    @Transactional
    public void logout(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    private AuthResponse createTokenResponse(AppUser user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refreshTokenValue = jwtTokenProvider.generateRefreshToken();

        long expiryMs = jwtTokenProvider.getRefreshTokenExpiryMs();
        LocalDateTime expiresAt = LocalDateTime.now().plusNanos(expiryMs * 1_000_000);
        RefreshToken refreshToken = new RefreshToken(user, refreshTokenValue, expiresAt);
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(accessToken, refreshTokenValue, toDto(user));
    }

    @Transactional
    public UserDto updateProfile(UUID userId, String name, String avatarUrl) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (name != null && !name.isBlank()) user.setName(name.trim());
        if (avatarUrl != null) user.setAvatarUrl(avatarUrl.isBlank() ? null : avatarUrl.trim());
        userRepository.save(user);
        return toDto(user);
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다.");
        }
        if (newPassword.length() < 8) {
            throw new IllegalArgumentException("새 비밀번호는 8자 이상이어야 합니다.");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    private UserDto toDto(AppUser user) {
        return new UserDto(user.getId(), user.getEmail(), user.getName(), user.getRole(), user.getAvatarUrl());
    }

    public record UserDto(UUID id, String email, String name, UserRole role, String avatarUrl) {}
    public record AuthResponse(String accessToken, String refreshToken, UserDto user) {}
}
