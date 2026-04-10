package com.example.rag.generation.workflow;

import com.example.rag.questionnaire.workflow.Requirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 결정론 기반 outline Planner.
 *
 * LLM을 사용하지 않는다. CategoryMapping을 기반으로 요구사항을 leaf에 1:1 배분하고,
 * 의무 항목과 배점을 결정론 규칙으로 할당한다.
 *
 * 입력:
 * - leaves: 확장 대상 leaf 목록
 * - requirements: 추출된 요구사항 (카테고리 포함)
 * - mandates: RFP mandates (mandatory items + evaluation weights)
 * - mapping: CategoryMapping (categoryToLeafKeys + leafKeyToRole)
 *
 * 출력: Map<leafKey, SectionAssignment>
 *
 * 이 서비스가 Step 4 아키텍처의 핵심. 이전의 LLM 기반 planExpansion을 대체한다.
 * Hill-climbing 패턴을 구조적으로 차단하는 것이 목적.
 */
@Service
public class RuleBasedPlanner {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedPlanner.class);

    /**
     * 모든 leaf에 대해 SectionAssignment를 생성.
     *
     * @param leafKeys    확장 대상 leaf key 목록 (순서 의미 있음)
     * @param requirements 요구사항 목록
     * @param mandates    RFP mandates (nullable)
     * @param mapping     CategoryMapping (empty면 heuristic fallback)
     * @return Map<leafKey, SectionAssignment>
     */
    public Map<String, SectionAssignment> plan(List<String> leafKeys,
                                                  List<Requirement> requirements,
                                                  RfpMandates mandates,
                                                  CategoryMapping mapping) {
        if (leafKeys == null || leafKeys.isEmpty()) {
            return Map.of();
        }
        List<Requirement> reqs = requirements != null ? requirements : List.of();
        RfpMandates rfpMandates = mandates != null ? mandates : RfpMandates.empty();
        CategoryMapping cm = mapping != null && !mapping.isEmpty() ? mapping : CategoryMapping.empty();

        // 1. 빈 SectionAssignment로 초기화
        Map<String, List<String>> leafToReqIds = new LinkedHashMap<>();
        Map<String, List<String>> leafToMandIds = new LinkedHashMap<>();
        for (String leafKey : leafKeys) {
            leafToReqIds.put(leafKey, new ArrayList<>());
            leafToMandIds.put(leafKey, new ArrayList<>());
        }

        // 2. 요구사항 배분 (category → leaf 매핑 사용)
        int assigned = 0;
        int unassigned = 0;
        List<String> unassignedIds = new ArrayList<>();
        for (Requirement req : reqs) {
            if (req.id() == null || req.id().isBlank()) continue;
            String targetLeaf = pickLeafForRequirement(req, cm, leafKeys);
            if (targetLeaf != null) {
                leafToReqIds.get(targetLeaf).add(req.id());
                assigned++;
            } else {
                unassigned++;
                unassignedIds.add(req.id());
            }
        }

        if (unassigned > 0) {
            log.warn("RuleBasedPlanner: {} requirements unassigned (no matching leaf): {}",
                    unassigned, unassignedIds.size() > 10 ?
                            unassignedIds.subList(0, 10) + " ..." : unassignedIds);
        }
        log.info("RuleBasedPlanner: {} requirements assigned to {} leaves ({} unassigned)",
                assigned, leafToReqIds.values().stream().filter(l -> !l.isEmpty()).count(), unassigned);

        // 3. 의무 항목 배분 (간단 heuristic: 가장 "기타" 성격이거나 matching role인 leaf)
        if (rfpMandates.hasMandatoryItems()) {
            for (MandatoryItem item : rfpMandates.mandatoryItems()) {
                String targetLeaf = pickLeafForMandatoryItem(item, cm, leafKeys);
                if (targetLeaf != null) {
                    leafToMandIds.get(targetLeaf).add(item.id());
                }
            }
        }

        // 4. 배점 할당 (evaluationWeights를 leaf에 매핑)
        Map<String, Integer> leafToWeight = assignWeights(leafKeys, rfpMandates, cm);

        // 5. SectionAssignment 조립
        Map<String, SectionAssignment> result = new LinkedHashMap<>();
        for (String leafKey : leafKeys) {
            String role = cm.roleOf(leafKey);
            SectionAssignment assignment = new SectionAssignment(
                    leafKey,
                    leafToReqIds.get(leafKey),
                    leafToMandIds.get(leafKey),
                    leafToWeight.get(leafKey),
                    role);
            result.put(leafKey, assignment);
        }

        if (log.isInfoEnabled()) {
            result.forEach((key, a) ->
                    log.info("  assignment[{}]: {} reqs, {} mands, weight={}, role={}",
                            key, a.requirementIds().size(), a.mandatoryItemIds().size(),
                            a.weight(), a.role()));
        }

        return result;
    }

    /**
     * 한 요구사항이 어느 leaf로 가야 하는지 결정.
     *
     * 우선순위:
     * 1. CategoryMapping에서 req.category → leaf key 직접 조회
     *    - 매핑 결과가 여러 leaf면 첫 번째 선택 (1:1 결정론 유지)
     * 2. 매핑 없으면 heuristic: leafKey/title keyword match
     * 3. 모두 실패하면 null
     */
    private String pickLeafForRequirement(Requirement req, CategoryMapping mapping, List<String> leafKeys) {
        // 1. Category 기반 매핑
        String category = req.category();
        if (category != null && !category.isBlank()) {
            List<String> candidates = mapping.leavesForCategory(category);
            if (!candidates.isEmpty()) {
                // 첫 번째 candidate 선택 (여러 개면 round-robin 확장 가능)
                String first = candidates.get(0);
                if (leafKeys.contains(first)) {
                    return first;
                }
                // candidates 중 실제 leafKeys에 있는 첫 번째
                for (String c : candidates) {
                    if (leafKeys.contains(c)) {
                        return c;
                    }
                }
            }
        }
        // 2. Heuristic fallback은 별도 로직 없이 null 반환 (validator가 unassigned를 catch)
        return null;
    }

    /**
     * 의무 항목이 어느 leaf로 가야 하는지 결정.
     * 현재는 단순 heuristic: "기타" 역할 leaf 우선, 없으면 마지막 leaf.
     * 향후 LLM 기반 개선 가능.
     */
    private String pickLeafForMandatoryItem(MandatoryItem item, CategoryMapping mapping, List<String> leafKeys) {
        // 우선순위 1: role=MISC인 leaf
        if (mapping.leafKeyToRole() != null) {
            for (String leafKey : leafKeys) {
                if ("MISC".equals(mapping.roleOf(leafKey))) {
                    return leafKey;
                }
            }
        }
        // 우선순위 2: 마지막 leaf
        return leafKeys.isEmpty() ? null : leafKeys.get(leafKeys.size() - 1);
    }

    /**
     * evaluationWeights를 leafKeys에 분배.
     *
     * 단순 전략: evaluationWeights의 각 항목이 categoryToLeafKeys 매핑과 일치하면 해당 leaf에 할당.
     * 매칭이 안 되면 leafKey title 기반 heuristic fallback.
     *
     * Phase B에서는 간단히: categoryToLeafKeys를 통해 매핑만 시도. 실패는 null.
     */
    private Map<String, Integer> assignWeights(List<String> leafKeys, RfpMandates mandates, CategoryMapping mapping) {
        Map<String, Integer> result = new HashMap<>();
        if (mandates == null || !mandates.hasEvaluationWeights()) {
            return result;
        }
        Map<String, Integer> weights = mandates.evaluationWeights();

        // 전략: evaluationWeights의 category-like key가 categoryToLeafKeys에 있으면 해당 leaf에 점수 할당
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            String evalCategory = entry.getKey();
            Integer score = entry.getValue();
            List<String> targetLeaves = mapping.leavesForCategory(evalCategory);
            if (targetLeaves.isEmpty()) continue;
            // 여러 leaf로 분할 → 균등 분배 (Phase C에서 정밀화 가능)
            int per = score / targetLeaves.size();
            int remainder = score % targetLeaves.size();
            for (int i = 0; i < targetLeaves.size(); i++) {
                String leafKey = targetLeaves.get(i);
                if (!leafKeys.contains(leafKey)) continue;
                int w = per + (i < remainder ? 1 : 0);
                result.merge(leafKey, w, Integer::sum);
            }
        }
        return result;
    }
}
