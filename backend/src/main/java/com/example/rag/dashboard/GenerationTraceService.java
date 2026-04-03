package com.example.rag.dashboard;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class GenerationTraceService {

    private final GenerationTraceRepository repository;

    public GenerationTraceService(GenerationTraceRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GenerationTraceEntity start(UUID jobId, String jobType, String stepName) {
        return repository.save(new GenerationTraceEntity(jobId, jobType, stepName));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(GenerationTraceEntity trace) {
        trace.complete();
        repository.save(trace);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(GenerationTraceEntity trace, String errorMessage) {
        trace.fail(errorMessage);
        repository.save(trace);
    }
}
