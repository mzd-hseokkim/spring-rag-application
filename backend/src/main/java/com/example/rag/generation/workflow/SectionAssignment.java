package com.example.rag.generation.workflow;

import java.util.List;

/**
 * Planner가 각 leaf에 할당한 작업 묶음.
 *
 * RuleBasedPlanner(결정론)가 생성하고, Enricher(LLM per-section)가 소비한다.
 *
 * Phase B의 핵심 데이터 모델: LLM이 결정하던 "어느 요구사항이 어느 leaf로 가는가"를
 * 코드가 결정하여 이 레코드로 표현.
 *
 * @param leafKey            대상 leaf의 key
 * @param requirementIds     이 leaf에 할당된 요구사항 ID 목록 (1:1 매핑, 중복 없음)
 * @param mandatoryItemIds   이 leaf에 할당된 의무 항목 ID 목록
 * @param weight             이 leaf의 평가 배점 (매칭 안 되면 null)
 * @param role               이 leaf의 관점 역할 (WHAT/HOW-tech 등, CategoryMapping에서 복사)
 */
public record SectionAssignment(
        String leafKey,
        List<String> requirementIds,
        List<String> mandatoryItemIds,
        Integer weight,
        String role
) {
    public static SectionAssignment empty(String leafKey) {
        return new SectionAssignment(leafKey, List.of(), List.of(), null, null);
    }

    public boolean hasRequirements() {
        return requirementIds != null && !requirementIds.isEmpty();
    }

    public boolean hasMandatoryItems() {
        return mandatoryItemIds != null && !mandatoryItemIds.isEmpty();
    }

    public boolean isEmpty() {
        return !hasRequirements() && !hasMandatoryItems() && weight == null;
    }
}
