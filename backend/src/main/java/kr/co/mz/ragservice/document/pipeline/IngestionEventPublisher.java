package kr.co.mz.ragservice.document.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IngestionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(IngestionEventPublisher.class);

    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID documentId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5분 타임아웃
        emitters.put(documentId, emitter);
        emitter.onCompletion(() -> emitters.remove(documentId));
        emitter.onTimeout(() -> emitters.remove(documentId));
        emitter.onError(e -> emitters.remove(documentId));
        return emitter;
    }

    public void publish(UUID documentId, String status, String message) {
        SseEmitter emitter = emitters.get(documentId);
        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data(Map.of("status", status, "message", message)));

            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                emitter.complete();
                emitters.remove(documentId);
            }
        } catch (IOException e) {
            log.warn("Failed to send SSE event for document {}", documentId);
            emitters.remove(documentId);
        }
    }
}
