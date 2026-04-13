package kr.co.mz.ragservice.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long accessTokenExpiry;
    private final long refreshTokenExpiry;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiry:1800000}") long accessTokenExpiry,
            @Value("${app.jwt.refresh-token-expiry:604800000}") long refreshTokenExpiry) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiry = accessTokenExpiry;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    public String generateAccessToken(UUID userId, String email, UserRole role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role.name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenExpiry))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }

    public long getRefreshTokenExpiryMs() {
        return refreshTokenExpiry;
    }

    public UUID getUserIdFromToken(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public String getRoleFromToken(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
