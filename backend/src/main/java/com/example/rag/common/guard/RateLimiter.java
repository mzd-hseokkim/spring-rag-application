package com.example.rag.common.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RateLimiter {

    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redisTemplate;
    private final int limitPerMinute;

    public RateLimiter(StringRedisTemplate redisTemplate,
                       @Value("${app.guard.rate-limit-per-minute:10}") int limitPerMinute) {
        this.redisTemplate = redisTemplate;
        this.limitPerMinute = limitPerMinute;
    }

    public void checkLimit(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofMinutes(1));
        }
        if (count != null && count > limitPerMinute) {
            throw new RateLimitExceededException("요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
        }
    }
}
