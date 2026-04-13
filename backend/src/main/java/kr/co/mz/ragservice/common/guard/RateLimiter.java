package kr.co.mz.ragservice.common.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Redis ZSET 기반 슬라이딩 윈도우 Rate Limiter.
 * 역할별/API별 한도를 지원한다.
 */
@Component
public class RateLimiter {

    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redisTemplate;
    private final int userLimitPerMinute;
    private final int adminLimitPerMinute;

    public RateLimiter(StringRedisTemplate redisTemplate,
                       @Value("${app.guard.rate-limit-per-minute:10}") int userLimitPerMinute,
                       @Value("${app.guard.admin-rate-limit-per-minute:30}") int adminLimitPerMinute) {
        this.redisTemplate = redisTemplate;
        this.userLimitPerMinute = userLimitPerMinute;
        this.adminLimitPerMinute = adminLimitPerMinute;
    }

    /**
     * 기존 호환 — sessionId 기반, 기본 한도 적용.
     */
    public void checkLimit(String sessionId) {
        checkLimit(sessionId, "chat", false);
    }

    /**
     * 역할별/API별 슬라이딩 윈도우 체크.
     * @return RateLimitInfo 남은 횟수 등 정보
     */
    public RateLimitInfo checkLimit(String userId, String apiType, boolean isAdmin) {
        int limit = isAdmin ? adminLimitPerMinute : userLimitPerMinute;
        String key = KEY_PREFIX + apiType + ":" + userId;
        long now = Instant.now().toEpochMilli();
        long windowStart = now - 60_000;

        // 만료된 엔트리 제거
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

        // 현재 윈도우 내 요청 수
        Long count = redisTemplate.opsForZSet().zCard(key);
        long currentCount = count != null ? count : 0;

        if (currentCount >= limit) {
            // 가장 오래된 요청의 만료 시각 계산
            Set<String> oldest = redisTemplate.opsForZSet().range(key, 0, 0);
            long resetMs = 60_000;
            if (oldest != null && !oldest.isEmpty()) {
                long oldestTime = Long.parseLong(oldest.iterator().next().split(":")[0]);
                resetMs = Math.max(1000, (oldestTime + 60_000) - now);
            }
            throw new RateLimitExceededException(
                    "요청이 너무 많습니다. %d초 후 다시 시도해주세요.".formatted(resetMs / 1000),
                    limit, 0, resetMs);
        }

        // 요청 기록 (score = timestamp, member = timestamp:uuid)
        String member = now + ":" + java.util.UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForZSet().add(key, member, now);
        redisTemplate.expire(key, Duration.ofMinutes(2));

        return new RateLimitInfo(limit, (int) (limit - currentCount - 1), 60_000);
    }

    public record RateLimitInfo(int limit, int remaining, long resetMs) {}
}
