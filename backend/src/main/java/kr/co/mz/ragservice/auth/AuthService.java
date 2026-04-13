package kr.co.mz.ragservice.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.co.mz.ragservice.common.RagException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {

    private static final String USER_NOT_FOUND = "사용자를 찾을 수 없습니다.";
    private static final String AVATAR_URL_PREFIX = "/uploads/avatars/";
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final long MAX_AVATAR_SIZE = 5L * 1024 * 1024; // 5MB

    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final Path avatarDir;

    public AuthService(AppUserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtTokenProvider jwtTokenProvider,
                       PasswordEncoder passwordEncoder,
                       @Value("${app.upload.avatar-dir}") String avatarDirPath) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.avatarDir = Paths.get(avatarDirPath).toAbsolutePath();
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
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
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
    public UserDto updateProfile(UUID userId, String name) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
        if (name != null && !name.isBlank()) user.setName(name.trim());
        userRepository.save(user);
        return toDto(user);
    }

    @Transactional
    public UserDto uploadAvatar(UUID userId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("파일이 비어 있습니다.");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new IllegalArgumentException("이미지 크기는 5MB 이하여야 합니다.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("지원하지 않는 이미지 형식입니다. (JPEG, PNG, GIF, WebP)");
        }

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));

        // delete old avatar file if exists
        deleteAvatarFile(user.getAvatarUrl());

        String ext = contentType.substring(contentType.indexOf('/') + 1);
        String filename = userId + "." + ext;
        try {
            Files.createDirectories(avatarDir);
            Files.write(avatarDir.resolve(filename), file.getBytes());
        } catch (IOException e) {
            throw new RagException("아바타 저장에 실패했습니다.", e);
        }

        user.setAvatarUrl(AVATAR_URL_PREFIX + filename);
        userRepository.save(user);
        return toDto(user);
    }

    @Transactional
    public UserDto deleteAvatar(UUID userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
        deleteAvatarFile(user.getAvatarUrl());
        user.setAvatarUrl(null);
        userRepository.save(user);
        return toDto(user);
    }

    private void deleteAvatarFile(String avatarUrl) {
        if (avatarUrl != null && avatarUrl.startsWith(AVATAR_URL_PREFIX)) {
            String filename = avatarUrl.substring(AVATAR_URL_PREFIX.length());
            try {
                Files.deleteIfExists(avatarDir.resolve(filename));
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
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
