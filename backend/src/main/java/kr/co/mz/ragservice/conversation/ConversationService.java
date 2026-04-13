package kr.co.mz.ragservice.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final String KEY_PREFIX = "conversation:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final int maxMessages;
    private final Duration ttl;

    public ConversationService(StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               ConversationRepository conversationRepository,
                               ConversationMessageRepository messageRepository,
                               @Value("${app.conversation.max-messages:20}") int maxMessages,
                               @Value("${app.conversation.ttl-minutes:60}") int ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.maxMessages = maxMessages;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    @Transactional
    public void addMessage(String sessionId, ConversationMessage message) {
        // Redis 캐시 저장
        String key = KEY_PREFIX + sessionId;
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.opsForList().trim(key, -maxMessages, -1);
            redisTemplate.expire(key, ttl);
        } catch (JsonProcessingException e) {
            throw new kr.co.mz.ragservice.common.RagException("Failed to serialize message", e);
        }

        // DB 영속 저장
        conversationRepository.findBySessionId(sessionId).ifPresent(conv -> {
            var entity = new ConversationMessageEntity(conv, message.role(), message.content(), message.sources());
            messageRepository.save(entity);
        });
    }

    public List<ConversationMessage> getHistory(String sessionId) {
        // 1. Redis 캐시 조회
        List<ConversationMessage> cached = getFromRedis(sessionId);
        if (!cached.isEmpty()) {
            return cached;
        }

        // 2. DB 폴백
        return conversationRepository.findBySessionId(sessionId)
                .map(conv -> {
                    List<ConversationMessage> messages = messageRepository
                            .findByConversationIdOrderByIdAsc(conv.getId()).stream()
                            .map(e -> new ConversationMessage(
                                    e.getRole(), e.getContent(),
                                    e.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant(),
                                    e.getSources()))
                            .toList();

                    // Redis 캐시 복원 (최근 N개만)
                    if (!messages.isEmpty()) {
                        restoreToRedis(sessionId, messages);
                    }
                    return messages;
                })
                .orElse(List.of());
    }

    @Transactional
    public void deleteSession(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }

    public void saveFeedback(String key, String rating) {
        redisTemplate.opsForValue().set(key, rating, Duration.ofDays(7));
    }

    private List<ConversationMessage> getFromRedis(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        List<String> items = redisTemplate.opsForList().range(key, 0, -1);
        if (items == null || items.isEmpty()) return List.of();

        return items.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, ConversationMessage.class);
                    } catch (JsonProcessingException e) {
                        throw new kr.co.mz.ragservice.common.RagException("Failed to deserialize message", e);
                    }
                })
                .toList();
    }

    private void restoreToRedis(String sessionId, List<ConversationMessage> messages) {
        String key = KEY_PREFIX + sessionId;
        try {
            List<ConversationMessage> recent = messages.size() > maxMessages
                    ? messages.subList(messages.size() - maxMessages, messages.size())
                    : messages;
            for (ConversationMessage msg : recent) {
                redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(msg));
            }
            redisTemplate.expire(key, ttl);
        } catch (JsonProcessingException e) {
            log.warn("Failed to restore messages to Redis for session {}", sessionId, e);
        }
    }
}
