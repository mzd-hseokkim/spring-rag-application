package com.example.rag.generation.workflow;

import java.util.List;
import java.util.Map;

/**
 * RFP에서 추출된 "제안서 작성 의무 항목"과 "평가 배점표"의 묶음.
 * RfpMandateExtractor가 생성하고, OutlineExtractor가 소비한다.
 */
public record RfpMandates(
        List<MandatoryItem> mandatoryItems,
        Map<String, Integer> evaluationWeights,   // 섹션 라벨 → 점수
        Integer totalScore                         // 총점 (예: 100). 미확보 시 null
) {
    public static RfpMandates empty() {
        return new RfpMandates(List.of(), Map.of(), null);
    }

    public boolean hasMandatoryItems() {
        return mandatoryItems != null && !mandatoryItems.isEmpty();
    }

    public boolean hasEvaluationWeights() {
        return evaluationWeights != null && !evaluationWeights.isEmpty();
    }

    public boolean isEmpty() {
        return !hasMandatoryItems() && !hasEvaluationWeights();
    }
}
