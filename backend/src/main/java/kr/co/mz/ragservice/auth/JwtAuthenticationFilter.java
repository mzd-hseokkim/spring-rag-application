package kr.co.mz.ragservice.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final AppUserRepository appUserRepository;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                    AppUserRepository appUserRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.appUserRepository = appUserRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null) {
            log.debug("Token found for {} (source: {}, length: {})",
                    request.getServletPath(),
                    request.getHeader("Authorization") != null ? "header" : "query",
                    token.length());
            if (jwtTokenProvider.validateToken(token)) {
                UUID userId = jwtTokenProvider.getUserIdFromToken(token);

                // DB에서 현재 역할을 조회 (역할 변경 즉시 반영)
                String role = appUserRepository.findById(userId)
                        .map(u -> u.getRole().name())
                        .orElse(jwtTokenProvider.getRoleFromToken(token));

                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                var authentication = new UsernamePasswordAuthenticationToken(
                        userId.toString(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            log.warn("Token present but authentication not set for {}", request.getServletPath());
        }
        if (token == null && request.getServletPath().startsWith("/api/generations")) {
            log.warn("No token for generation endpoint: {} query: {}", request.getServletPath(), request.getQueryString());
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        // 1) Authorization 헤더
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        // 2) Query param (SSE 등 EventSource에서 헤더 설정 불가 시)
        String queryToken = request.getParameter("token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken;
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/api/auth/register")
                || path.startsWith("/api/auth/login")
                || path.startsWith("/api/auth/refresh");
    }
}
