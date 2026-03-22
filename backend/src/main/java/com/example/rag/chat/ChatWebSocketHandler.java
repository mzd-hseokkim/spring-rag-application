package com.example.rag.chat;

import com.example.rag.common.guard.InputValidator;
import com.example.rag.common.guard.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import reactor.core.Disposable;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class ChatWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String TYPE_ERROR = "error";
    private static final String KEY_MESSAGE = "message";

    private final ChatService chatService;
    private final InputValidator inputValidator;
    private final RateLimiter rateLimiter;
    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, Disposable> activeStreams = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatService chatService,
                                 InputValidator inputValidator,
                                 RateLimiter rateLimiter,
                                 SimpMessagingTemplate messagingTemplate) {
        this.chatService = chatService;
        this.inputValidator = inputValidator;
        this.rateLimiter = rateLimiter;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/chat/send")
    public void handleChat(ChatWsRequest request, Principal principal) {
        String userId = principal.getName();
        String sessionId = request.sessionId();

        try {
            inputValidator.validate(request.message());
            rateLimiter.checkLimit(userId);
        } catch (Exception e) {
            sendToUser(userId, Map.of("type", TYPE_ERROR, KEY_MESSAGE, e.getMessage()));
            return;
        }

        // 기존 스트림 정리
        cancelIfActive(sessionId);

        // 비동기 실행
        Thread.startVirtualThread(() -> {
            try {
                List<UUID> tagIds = request.tagIds() != null
                        ? request.tagIds().stream().map(UUID::fromString).toList()
                        : List.of();
                List<UUID> collectionIds = request.collectionIds() != null
                        ? request.collectionIds().stream().map(UUID::fromString).toList()
                        : List.of();
                boolean includePublic = request.includePublicDocs() == null || request.includePublicDocs();

                ChatService.ChatResponse response = chatService.chat(
                        sessionId, request.message(), request.modelId(),
                        UUID.fromString(userId), includePublic, tagIds, collectionIds,
                        step -> sendToUser(userId, Map.of(
                                "type", "agent_step",
                                "step", step.step(),
                                KEY_MESSAGE, step.message()
                        ))
                );

                Disposable subscription = response.tokens().subscribe(
                        token -> sendToUser(userId, Map.of("type", "token", "content", token)),
                        error -> {
                            log.error("Chat stream error for session {}: {}", sessionId, error.getMessage());
                            sendToUser(userId, Map.of("type", TYPE_ERROR, KEY_MESSAGE, error.getMessage()));
                            activeStreams.remove(sessionId);
                        },
                        () -> {
                            sendToUser(userId, Map.of("type", "sources", "sources", response.sources()));
                            sendToUser(userId, Map.of("type", "done"));
                            activeStreams.remove(sessionId);
                        }
                );

                activeStreams.put(sessionId, subscription);

            } catch (Exception e) {
                log.error("Chat error for session {}: {}", sessionId, e.getMessage());
                sendToUser(userId, Map.of("type", TYPE_ERROR, KEY_MESSAGE, e.getMessage()));
            }
        });
    }

    @MessageMapping("/chat/stop")
    public void handleStop(StopRequest request, Principal principal) {
        String sessionId = request.sessionId();
        Disposable sub = activeStreams.remove(sessionId);
        if (sub != null && !sub.isDisposed()) {
            sub.dispose();
            log.info("Chat stream stopped for session {}", sessionId);
        }
        sendToUser(principal.getName(), Map.of("type", "stopped"));
    }

    private void cancelIfActive(String sessionId) {
        Disposable sub = activeStreams.remove(sessionId);
        if (sub != null && !sub.isDisposed()) {
            sub.dispose();
        }
    }

    private void sendToUser(String userId, Map<String, Object> payload) {
        messagingTemplate.convertAndSendToUser(userId, "/queue/chat", payload);
    }

    public record ChatWsRequest(String sessionId, String message, String modelId,
                                 Boolean includePublicDocs,
                                 List<String> tagIds, List<String> collectionIds) {}

    public record StopRequest(String sessionId) {}
}
