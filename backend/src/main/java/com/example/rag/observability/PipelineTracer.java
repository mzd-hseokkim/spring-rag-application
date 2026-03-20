package com.example.rag.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PipelineTracer {

    private static final Logger log = LoggerFactory.getLogger("pipeline.trace");

    private final ObjectMapper objectMapper;

    public PipelineTracer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void logTrace(TraceContext context) {
        try {
            String json = objectMapper.writeValueAsString(context.toMap());
            log.info("{}", json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize trace: {}", e.getMessage());
        }
    }
}
