package com.example.rag.chat;

import com.example.rag.audit.AuditService;
import com.example.rag.common.guard.InputValidationException;
import com.example.rag.common.guard.InputValidator;
import com.example.rag.common.guard.RateLimitExceededException;
import com.example.rag.common.guard.RateLimiter;
import com.example.rag.conversation.ConversationMessage;
import com.example.rag.conversation.ConversationService;
import jakarta.servlet.http.HttpServletResponse;
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

    private static final String KEY_MESSAGE = "message";

    private final ChatService chatService;
    private final ConversationService conversationService;
    private final InputValidator inputValidator;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;

    public ChatController(ChatService chatService,
                          ConversationService conversationService,
                          InputValidator inputValidator,
                          RateLimiter rateLimiter,
                          AuditService auditService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
        this.inputValidator = inputValidator;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request, Authentication auth,
                           HttpServletResponse httpResponse) {
        inputValidator.validate(request.message());
        java.util.UUID userId = java.util.UUID.fromString(auth.getName());
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        RateLimiter.RateLimitInfo rateInfo = rateLimiter.checkLimit(userId.toString(), "chat", isAdmin);
        httpResponse.setHeader("X-RateLimit-Limit", String.valueOf(rateInfo.limit()));
        httpResponse.setHeader("X-RateLimit-Remaining", String.valueOf(rateInfo.remaining()));
        httpResponse.setHeader("X-RateLimit-Reset", String.valueOf(rateInfo.resetMs() / 1000));

        SseEmitter emitter = new SseEmitter(300_000L);

        // Heartbeat: 10초마다 SSE comment를 보내서 연결 유지
        @SuppressWarnings("resource") // shutdown via emitter callbacks (onCompletion/onTimeout/onError)
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        emitter.onCompletion(heartbeat::shutdown);
        emitter.onTimeout(heartbeat::shutdown);
        emitter.onError(e -> heartbeat.shutdown());
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                heartbeat.shutdown();
            }
        }, 10, 10, TimeUnit.SECONDS);

        CompletableFuture.runAsync(() -> streamChatResponse(request, userId, emitter, heartbeat));

        return emitter;
    }

    private void streamChatResponse(ChatRequest request, java.util.UUID userId,
                                     SseEmitter emitter, ScheduledExecutorService heartbeat) {
        try {
            boolean includePublic = request.includePublicDocs() == null || request.includePublicDocs();
            List<java.util.UUID> tagIds = request.tagIds() != null
                    ? request.tagIds().stream().map(java.util.UUID::fromString).toList()
                    : List.of();
            List<java.util.UUID> collectionIds = request.collectionIds() != null
                    ? request.collectionIds().stream().map(java.util.UUID::fromString).toList()
                    : List.of();
            boolean enableWebSearch = request.enableWebSearch() != null && request.enableWebSearch();
            ChatService.ChatResponse response = chatService.chat(
                    request.sessionId(), request.message(), request.modelId(),
                    userId, includePublic, tagIds, collectionIds, enableWebSearch, step -> {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("agent_step")
                                    .data(Map.of("step", step.step(), KEY_MESSAGE, step.message())));
                        } catch (IOException e) {
                            // SSE send failure during agent step — stream cleanup on completion
                        }
                    });

            subscribeTokenStream(response, emitter, heartbeat);
        } catch (Exception e) {
            heartbeat.shutdown();
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of(KEY_MESSAGE, ChatErrorMessages.toUserMessage(e))));
            } catch (IOException ex) {
                // SSE send failure during error handling
            }
            emitter.completeWithError(e);
        }
    }

    private void subscribeTokenStream(ChatService.ChatResponse response,
                                       SseEmitter emitter, ScheduledExecutorService heartbeat) {
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
                                .data(Map.of(KEY_MESSAGE, ChatErrorMessages.toUserMessage(error))));
                    } catch (IOException e) {
                        // SSE send failure during error reporting
                    }
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
        return Map.of(KEY_MESSAGE, e.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Map<String, String> handleRateLimit(RateLimitExceededException e, HttpServletResponse response,
                                                Authentication auth) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(e.getLimit()));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setHeader("Retry-After", String.valueOf(e.getRetryAfterMs() / 1000));
        if (auth != null) {
            auditService.log(java.util.UUID.fromString(auth.getName()), null,
                    "RATE_LIMIT_EXCEEDED", "CHAT", null);
        }
        return Map.of(KEY_MESSAGE, e.getMessage());
    }

    record ChatRequest(String sessionId, String message, String modelId, Boolean includePublicDocs,
                       java.util.List<String> tagIds, java.util.List<String> collectionIds,
                       Boolean enableWebSearch) {}
    record FeedbackRequest(String sessionId, int messageIndex, String rating) {}
}
