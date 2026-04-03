package com.example.rag.generation.workflow;

import com.example.rag.generation.GenerationEmitterManager;
import com.example.rag.generation.GenerationJob;
import com.example.rag.generation.GenerationJobRepository;
import com.example.rag.generation.GenerationStatus;
import com.example.rag.generation.dto.GenerationProgressEvent;
import com.example.rag.questionnaire.workflow.Requirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@Component
public class WorkflowEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventEmitter.class);
    private static final String FIELD_REQUIREMENTS = "requirements";

    private final GenerationEmitterManager emitterManager;
    private final GenerationJobRepository jobRepository;

    public WorkflowEventEmitter(GenerationEmitterManager emitterManager,
                                GenerationJobRepository jobRepository) {
        this.emitterManager = emitterManager;
        this.jobRepository = jobRepository;
    }

    public void updateStatus(GenerationJob job, GenerationStatus status) {
        job.setStatus(status);
        jobRepository.save(job);
    }

    public void emitRequirements(GenerationJob job, List<Requirement> requirements) {
        SseEmitter emitter = emitterManager.get(job.getId());
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name(FIELD_REQUIREMENTS)
                    .data(requirements));
        } catch (IllegalStateException e) {
            log.debug("SSE emitter already completed for job {}: {}", job.getId(), e.getMessage());
            emitterManager.remove(job.getId());
        } catch (IOException e) {
            log.debug("SSE client disconnected for job {}: {}", job.getId(), e.getMessage());
            emitterManager.remove(job.getId());
        }
    }

    public void emitEvent(GenerationJob job, GenerationProgressEvent event) {
        SseEmitter emitter = emitterManager.get(job.getId());
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(event.eventType())
                    .data(event));
            if ("complete".equals(event.eventType()) || "error".equals(event.eventType())) {
                emitter.complete();
            }
        } catch (IllegalStateException e) {
            // emitter가 이미 complete된 경우 — 클라이언트 연결 해제 시 발생
            log.debug("SSE emitter already completed for job {}: {}", job.getId(), e.getMessage());
            emitterManager.remove(job.getId());
        } catch (IOException e) {
            // 클라이언트가 연결을 끊은 경우 — emitter 제거하여 이후 전송 시도 방지
            log.debug("SSE client disconnected for job {}: {}", job.getId(), e.getMessage());
            emitterManager.remove(job.getId());
        }
    }
}
