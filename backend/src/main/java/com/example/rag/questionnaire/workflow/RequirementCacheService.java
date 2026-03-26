package com.example.rag.questionnaire.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 문서 ID 기반 요구사항 추출 결과 2중 캐시.
 * L1: Redis (빠른 조회, TTL 기반)
 * L2: PostgreSQL (영구 저장, 문서 재인제스트 시에만 무효화)
 *
 * 문서 생성(generation)과 질의서(questionnaire) 양쪽에서 공유한다.
 */
@Service
public class RequirementCacheService {

    private static final Logger log = LoggerFactory.getLogger(RequirementCacheService.class);
    private static final String REDIS_KEY_PREFIX = "requirements:extracted:";
    private static final TypeReference<List<Requirement>> REQ_LIST_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Duration redisTtl;

    public RequirementCacheService(StringRedisTemplate redisTemplate,
                                    JdbcTemplate jdbcTemplate,
                                    ObjectMapper objectMapper,
                                    @Value("${app.requirements.redis-ttl-hours:72}") int redisTtlHours) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.redisTtl = Duration.ofHours(redisTtlHours);
    }

    /**
     * L1(Redis) → L2(PostgreSQL) 순으로 조회.
     * L2 히트 시 L1에 복원한다.
     */
    public List<Requirement> get(List<UUID> documentIds) {
        String hash = buildHash(documentIds);

        // L1: Redis
        List<Requirement> fromRedis = getFromRedis(hash);
        if (!fromRedis.isEmpty()) {
            log.info("Requirement cache L1 HIT: {} ({} requirements)", hash, fromRedis.size());
            return fromRedis;
        }

        // L2: PostgreSQL
        List<Requirement> fromDb = getFromDb(hash);
        if (!fromDb.isEmpty()) {
            log.info("Requirement cache L2 HIT: {} ({} requirements)", hash, fromDb.size());
            putToRedis(hash, fromDb);
            return fromDb;
        }

        return List.of();
    }

    /**
     * L1 + L2 모두에 저장.
     */
    public void put(List<UUID> documentIds, List<Requirement> requirements) {
        String hash = buildHash(documentIds);
        putToRedis(hash, requirements);
        putToDb(hash, documentIds, requirements);
    }

    /**
     * 특정 문서 ID를 포함하는 모든 캐시 엔트리를 무효화한다.
     * L2(PostgreSQL)에서 해당 문서가 포함된 모든 행을 찾아 삭제하고,
     * 각 행의 해시로 L1(Redis)도 함께 삭제한다.
     */
    public void evict(List<UUID> documentIds) {
        for (UUID docId : documentIds) {
            // PostgreSQL에서 해당 문서를 포함하는 모든 캐시 키 조회 후 삭제
            List<String> evictedHashes = jdbcTemplate.queryForList(
                    "DELETE FROM requirement_cache WHERE ? = ANY(document_ids) RETURNING doc_ids_hash",
                    String.class, docId);

            for (String hash : evictedHashes) {
                redisTemplate.delete(REDIS_KEY_PREFIX + hash);
            }

            if (!evictedHashes.isEmpty()) {
                log.info("Requirement cache EVICT for doc {}: {} entries removed", docId, evictedHashes.size());
            }
        }
    }

    /**
     * 모든 요구사항 캐시를 삭제한다 (프롬프트 변경 등으로 전체 재추출이 필요한 경우).
     * @return 삭제된 L2 엔트리 수
     */
    public int evictAll() {
        // L2: PostgreSQL 전체 삭제
        int dbDeleted = jdbcTemplate.update("DELETE FROM requirement_cache");

        // L1: Redis 패턴 매칭 삭제
        var keys = redisTemplate.keys(REDIS_KEY_PREFIX + "*");
        int redisDeleted = 0;
        if (keys != null && !keys.isEmpty()) {
            redisDeleted = keys.size();
            redisTemplate.delete(keys);
        }

        log.info("Requirement cache EVICT ALL: {} L2 entries, {} L1 keys removed", dbDeleted, redisDeleted);
        return dbDeleted;
    }

    // ── L1: Redis ──

    private List<Requirement> getFromRedis(String hash) {
        try {
            String cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + hash);
            if (cached == null) return List.of();
            return objectMapper.readValue(cached, REQ_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Redis requirement cache read failed: {}", e.getMessage());
            return List.of();
        }
    }

    private void putToRedis(String hash, List<Requirement> requirements) {
        try {
            String json = objectMapper.writeValueAsString(requirements);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + hash, json, redisTtl);
        } catch (Exception e) {
            log.warn("Redis requirement cache write failed: {}", e.getMessage());
        }
    }

    // ── L2: PostgreSQL ──

    private List<Requirement> getFromDb(String hash) {
        try {
            List<String> results = jdbcTemplate.queryForList(
                    "SELECT requirements::text FROM requirement_cache WHERE doc_ids_hash = ?",
                    String.class, hash);
            if (results.isEmpty()) return List.of();
            return objectMapper.readValue(results.get(0), REQ_LIST_TYPE);
        } catch (Exception e) {
            log.warn("PostgreSQL requirement cache read failed: {}", e.getMessage());
            return List.of();
        }
    }

    private void putToDb(String hash, List<UUID> documentIds, List<Requirement> requirements) {
        try {
            String json = objectMapper.writeValueAsString(requirements);
            UUID[] docIdArray = documentIds.stream().sorted().toArray(UUID[]::new);

            jdbcTemplate.update("""
                    INSERT INTO requirement_cache (doc_ids_hash, document_ids, requirements, updated_at)
                    VALUES (?, ?, ?::jsonb, now())
                    ON CONFLICT (doc_ids_hash)
                    DO UPDATE SET requirements = EXCLUDED.requirements, updated_at = now()
                    """,
                    hash, docIdArray, json);
            log.info("Requirement cache L2 PUT: {} ({} requirements)", hash, requirements.size());
        } catch (Exception e) {
            log.warn("PostgreSQL requirement cache write failed: {}", e.getMessage());
        }
    }

    // ── 공통 ──

    private String buildHash(List<UUID> documentIds) {
        List<String> sorted = documentIds.stream()
                .map(UUID::toString)
                .sorted()
                .toList();
        return sha256(String.join(",", sorted));
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
