package com.example.rag.questionnaire.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentAnalysisCacheService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAnalysisCacheService.class);
    private static final String KEY_PREFIX = "questionnaire:analysis:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public DocumentAnalysisCacheService(StringRedisTemplate redisTemplate,
                                         @Value("${app.questionnaire.analysis-cache-ttl-hours:24}") int ttlHours) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofHours(ttlHours);
    }

    public String get(List<UUID> targetDocIds, String userInput) {
        String key = buildKey(targetDocIds, userInput);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.info("Document analysis cache HIT: {}", key);
        }
        return cached;
    }

    public void put(List<UUID> targetDocIds, String userInput, String analysis) {
        String key = buildKey(targetDocIds, userInput);
        redisTemplate.opsForValue().set(key, analysis, ttl);
        log.info("Document analysis cached: {} ({} chars, TTL {}h)", key, analysis.length(), ttl.toHours());
    }

    private String buildKey(List<UUID> targetDocIds, String userInput) {
        List<String> sortedIds = targetDocIds.stream()
                .map(UUID::toString)
                .sorted()
                .toList();
        String raw = String.join(",", sortedIds) + "|" + (userInput != null ? userInput : "");
        return KEY_PREFIX + sha256(raw);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
