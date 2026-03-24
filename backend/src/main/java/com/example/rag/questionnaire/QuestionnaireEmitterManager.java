package com.example.rag.questionnaire;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class QuestionnaireEmitterManager {

    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public void register(UUID jobId, SseEmitter emitter) {
        emitters.put(jobId, emitter);
        emitter.onCompletion(() -> emitters.remove(jobId));
        emitter.onTimeout(() -> emitters.remove(jobId));
        emitter.onError(e -> emitters.remove(jobId));
    }

    public SseEmitter get(UUID jobId) {
        return emitters.get(jobId);
    }
}
