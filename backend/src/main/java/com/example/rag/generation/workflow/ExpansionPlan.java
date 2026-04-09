package com.example.rag.generation.workflow;

import java.util.List;

/**
 * outline 확장 단계에서 각 leaf에 할당된 확장 계획.
 * OutlineExtractor.planExpansion()이 전역 LLM 호출로 생성하고,
 * expandSection()이 이를 받아 해당 leaf의 children을 구성한다.
 *
 * 목적: ledger 기반 post-hoc 회피 대신 up-front 할당으로 중복을 원천 차단.
 */
public record ExpansionPlan(
        Integer weight,                      // 이 섹션에 할당된 RFP 배점 (null = 미매칭)
        List<String> topics,                 // 이 섹션이 다뤄야 할 주제 목록 (다른 섹션과 중복 없이 배분됨)
        List<String> mandatoryItemIds        // 이 섹션에 배치된 의무 작성 항목 ID
) {
    public static ExpansionPlan empty() {
        return new ExpansionPlan(null, List.of(), List.of());
    }

    public boolean hasWeight() {
        return weight != null;
    }

    public boolean hasTopics() {
        return topics != null && !topics.isEmpty();
    }

    public boolean hasMandatoryItems() {
        return mandatoryItemIds != null && !mandatoryItemIds.isEmpty();
    }

    public boolean isEmpty() {
        return !hasWeight() && !hasTopics() && !hasMandatoryItems();
    }
}
