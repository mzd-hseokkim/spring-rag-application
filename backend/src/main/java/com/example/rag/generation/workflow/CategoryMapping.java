package com.example.rag.generation.workflow;

import java.util.List;
import java.util.Map;

/**
 * 요구사항 카테고리 → outline leaf 매핑.
 *
 * RuleBasedPlanner가 이 매핑을 사용하여 요구사항을 leaf에 결정론적으로 배분한다.
 *
 * 매핑은 `CategoryMappingDeriver`가 1회 LLM 호출로 생성하거나,
 * 사용자가 직접 입력할 수 있다. 생성 후에는 코드가 결정론으로 사용.
 *
 * @param categoryToLeafKeys 카테고리명 → 해당 카테고리가 배치될 leaf key 목록 (1:N 허용)
 * @param leafKeyToRole      leaf key → 관점 역할 (예: "WHAT", "HOW-tech", "WHY", "HOW-method"). 미분류 시 null
 */
public record CategoryMapping(
        Map<String, List<String>> categoryToLeafKeys,
        Map<String, String> leafKeyToRole
) {
    public static CategoryMapping empty() {
        return new CategoryMapping(Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return categoryToLeafKeys == null || categoryToLeafKeys.isEmpty();
    }

    public List<String> leavesForCategory(String category) {
        if (categoryToLeafKeys == null) return List.of();
        return categoryToLeafKeys.getOrDefault(category, List.of());
    }

    public String roleOf(String leafKey) {
        if (leafKeyToRole == null) return null;
        return leafKeyToRole.get(leafKey);
    }
}
