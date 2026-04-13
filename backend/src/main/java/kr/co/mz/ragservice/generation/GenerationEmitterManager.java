package kr.co.mz.ragservice.generation;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class GenerationEmitterManager {

    private static final Logger log = LoggerFactory.getLogger(GenerationEmitterManager.class);
    /** SSE keepalive 주기 (초). 브라우저/proxy의 idle timeout보다 짧아야 함. */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15L;

    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeatExecutor;

    @PostConstruct
    public void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("SSE heartbeat scheduler started (interval={}s)", HEARTBEAT_INTERVAL_SECONDS);
    }

    @PreDestroy
    public void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
    }

    public void register(UUID jobId, SseEmitter emitter) {
        emitters.put(jobId, emitter);
        emitter.onCompletion(() -> emitters.remove(jobId));
        emitter.onTimeout(() -> emitters.remove(jobId));
        emitter.onError(e -> emitters.remove(jobId));
    }

    public SseEmitter get(UUID jobId) {
        return emitters.get(jobId);
    }

    public void remove(UUID jobId) {
        emitters.remove(jobId);
    }

    /**
     * 모든 등록된 emitter에 SSE comment 형식의 keepalive를 발행한다.
     * 브라우저/intermediate proxy가 SSE 연결을 idle로 판단하지 않도록 유지.
     *
     * SSE comment는 ":" 로 시작하는 라인이고, EventSource는 데이터 이벤트로 처리하지 않으므로
     * 프론트엔드 핸들러에 영향 없음.
     */
    private void sendHeartbeat() {
        if (emitters.isEmpty()) return;
        for (Map.Entry<UUID, SseEmitter> entry : emitters.entrySet()) {
            UUID jobId = entry.getKey();
            SseEmitter emitter = entry.getValue();
            try {
                // SSE comment 라인 (": ping\n\n") — keepalive 용
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IllegalStateException e) {
                log.debug("Heartbeat: emitter already completed for job {}, removing", jobId);
                emitters.remove(jobId);
            } catch (IOException e) {
                log.debug("Heartbeat: SSE client disconnected for job {}, removing", jobId);
                emitters.remove(jobId);
            } catch (Exception e) {
                log.warn("Heartbeat: unexpected error for job {}: {}", jobId, e.getMessage());
                emitters.remove(jobId);
            }
        }
    }
}
