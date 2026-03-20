package com.example.rag.chat;

import com.example.rag.conversation.ConversationMessage;
import com.example.rag.conversation.ConversationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;

    public ChatController(ChatService chatService, ConversationService conversationService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);

        ChatService.ChatResponse response = chatService.chat(request.sessionId(), request.message());

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

    record ChatRequest(String sessionId, String message) {}
}
