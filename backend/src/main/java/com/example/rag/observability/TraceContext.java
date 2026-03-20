package com.example.rag.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TraceContext {

    private final String traceId;
    private final String sessionId;
    private final String originalQuery;
    private final Instant startTime;
    private final List<TraceStep> steps = new ArrayList<>();
    private Instant currentStepStart;
    private String currentStepName;

    public TraceContext(String sessionId, String originalQuery) {
        this.traceId = UUID.randomUUID().toString().substring(0, 8);
        this.sessionId = sessionId;
        this.originalQuery = originalQuery;
        this.startTime = Instant.now();
    }

    public void startStep(String name) {
        this.currentStepName = name;
        this.currentStepStart = Instant.now();
    }

    public void endStep(Map<String, Object> metadata) {
        if (currentStepName == null) return;
        steps.add(new TraceStep(
                currentStepName,
                Duration.between(currentStepStart, Instant.now()).toMillis(),
                metadata
        ));
        currentStepName = null;
    }

    public void endStep() {
        endStep(Map.of());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("traceId", traceId);
        map.put("sessionId", sessionId);
        map.put("query", originalQuery);
        map.put("totalLatencyMs", Duration.between(startTime, Instant.now()).toMillis());
        map.put("steps", steps.stream().map(TraceStep::toMap).toList());
        return map;
    }

    public String getTraceId() { return traceId; }

    record TraceStep(String name, long latencyMs, Map<String, Object> metadata) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("latencyMs", latencyMs);
            map.putAll(metadata);
            return map;
        }
    }
}
