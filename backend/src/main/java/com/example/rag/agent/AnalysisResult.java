package com.example.rag.agent;

import java.util.List;
import java.util.UUID;

/**
 * Stage 1 (분석) 결과 — compress + decide + decompose를 하나의 LLM 호출로 통합.
 */
public record AnalysisResult(
        AgentAction action,
        String searchQuery,
        List<UUID> targetDocumentIds,
        List<String> subQueries
) {
    public boolean isMultiStep() {
        return subQueries != null && subQueries.size() > 1;
    }
}
