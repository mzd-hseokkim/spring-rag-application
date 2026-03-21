package com.example.rag.chat;

import com.example.rag.common.guard.InputValidationException;
import com.example.rag.common.guard.InputValidator;
import com.example.rag.common.guard.RateLimitExceededException;
import com.example.rag.common.guard.RateLimiter;
import com.example.rag.conversation.ConversationMessage;
import com.example.rag.conversation.ConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;
    private final InputValidator inputValidator;
    private final RateLimiter rateLimiter;

    public ChatController(ChatService chatService,
                          ConversationService conversationService,
                          InputValidator inputValidator,
                          RateLimiter rateLimiter) {
        this.chatService = chatService;
        this.conversationService = conversationService;
        this.inputValidator = inputValidator;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request, Authentication auth) {
        inputValidator.validate(request.message());
        java.util.UUID userId = java.util.UUID.fromString(auth.getName());
        rateLimiter.checkLimit(userId.toString());

        SseEmitter emitter = new SseEmitter(300_000L);

        // Heartbeat: 10초마다 SSE comment를 보내서 연결 유지
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                heartbeat.shutdown();
            }
        }, 10, 10, TimeUnit.SECONDS);

        // 비동기 실행: emitter가 먼저 클라이언트에 반환된 후 agent step 이벤트가 실시간으로 전송됨
        CompletableFuture.runAsync(() -> {
            try {
                boolean includePublic = request.includePublicDocs() == null || request.includePublicDocs();
                List<java.util.UUID> tagIds = request.tagIds() != null
                        ? request.tagIds().stream().map(java.util.UUID::fromString).toList()
                        : List.of();
                List<java.util.UUID> collectionIds = request.collectionIds() != null
                        ? request.collectionIds().stream().map(java.util.UUID::fromString).toList()
                        : List.of();
                ChatService.ChatResponse response = chatService.chat(
                        request.sessionId(), request.message(), request.modelId(),
                        userId, includePublic, tagIds, collectionIds, step -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("agent_step")
                                        .data(Map.of("step", step.step(), "message", step.message())));
                            } catch (IOException ignored) {}
                        });

                response.tokens().subscribe(
                        token -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("token")
                                        .data(Map.of("content", token)));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(Map.of("message", error.getMessage())));
                            } catch (IOException ignored) {}
                            emitter.completeWithError(error);
                        },
                        () -> {
                            heartbeat.shutdown();
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("sources")
                                        .data(response.sources()));
                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data(Map.of()));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        }
                );
            } catch (Exception e) {
                heartbeat.shutdown();
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", e.getMessage())));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<ConversationMessage> getHistory(@PathVariable String sessionId) {
        return conversationService.getHistory(sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void deleteSession(@PathVariable String sessionId) {
        conversationService.deleteSession(sessionId);
    }

    @PostMapping("/feedback")
    public void submitFeedback(@RequestBody FeedbackRequest request) {
        String key = "feedback:%s:%d".formatted(request.sessionId(), request.messageIndex());
        conversationService.saveFeedback(key, request.rating());
    }

    @ExceptionHandler(InputValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidation(InputValidationException e) {
        return Map.of("message", e.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Map<String, String> handleRateLimit(RateLimitExceededException e) {
        return Map.of("message", e.getMessage());
    }

    record ChatRequest(String sessionId, String message, String modelId, Boolean includePublicDocs,
                       java.util.List<String> tagIds, java.util.List<String> collectionIds) {}
    record FeedbackRequest(String sessionId, int messageIndex, String rating) {}
}
