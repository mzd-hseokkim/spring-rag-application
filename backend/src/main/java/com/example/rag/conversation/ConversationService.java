package com.example.rag.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class ConversationService {

    private static final String KEY_PREFIX = "conversation:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxMessages;
    private final Duration ttl;

    public ConversationService(StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               @Value("${app.conversation.max-messages:20}") int maxMessages,
                               @Value("${app.conversation.ttl-minutes:60}") int ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxMessages = maxMessages;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public void addMessage(String sessionId, ConversationMessage message) {
        String key = KEY_PREFIX + sessionId;
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.opsForList().trim(key, -maxMessages, -1);
            redisTemplate.expire(key, ttl);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize message", e);
        }
    }

    public List<ConversationMessage> getHistory(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        List<String> items = redisTemplate.opsForList().range(key, 0, -1);
        if (items == null || items.isEmpty()) return List.of();

        return items.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, ConversationMessage.class);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException("Failed to deserialize message", e);
                    }
                })
                .toList();
    }

    public void deleteSession(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }

    public void saveFeedback(String key, String rating) {
        redisTemplate.opsForValue().set(key, rating, Duration.ofDays(7));
    }
}
