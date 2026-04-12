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

    /** 한 leaf가 받을 수 있는 권장 최대 요구사항 수. 초과 시 다음 candidate으로 spillover. */
    private static final int LEAF_REQ_SOFT_CAP = 10;

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

        Map<String, List<String>> leafToReqIds = initializeLeafMap(leafKeys);
        Map<String, List<String>> leafToMandIds = initializeLeafMap(leafKeys);

        assignRequirementsToLeaves(reqs, cm, leafKeys, leafToReqIds);
        assignMandatoryItemsToLeaves(rfpMandates, cm, leafKeys, leafToMandIds);
        Map<String, Integer> leafToWeight = assignWeights(leafKeys, rfpMandates, cm);

        Map<String, SectionAssignment> result = assembleAssignments(leafKeys, leafToReqIds, leafToMandIds, leafToWeight, cm);
        logAssignments(result);
        return result;
    }

    private Map<String, List<String>> initializeLeafMap(List<String> leafKeys) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String leafKey : leafKeys) {
            map.put(leafKey, new ArrayList<>());
        }
        return map;
    }

    private void assignRequirementsToLeaves(List<Requirement> reqs, CategoryMapping cm,
                                              List<String> leafKeys, Map<String, List<String>> leafToReqIds) {
        Map<String, Integer> categoryRoundRobin = new HashMap<>();
        int assigned = 0;
        int unassigned = 0;
        List<String> unassignedIds = new ArrayList<>();
        for (Requirement req : reqs) {
            if (req.id() == null || req.id().isBlank()) continue;
            String targetLeaf = pickLeafForRequirement(req, cm, leafKeys, leafToReqIds, categoryRoundRobin);
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
    }

    private void assignMandatoryItemsToLeaves(RfpMandates rfpMandates, CategoryMapping cm,
                                                 List<String> leafKeys, Map<String, List<String>> leafToMandIds) {
        if (!rfpMandates.hasMandatoryItems()) return;
        for (MandatoryItem item : rfpMandates.mandatoryItems()) {
            String targetLeaf = pickLeafForMandatoryItem(cm, leafKeys);
            if (targetLeaf != null) {
                leafToMandIds.get(targetLeaf).add(item.id());
            }
        }
    }

    private Map<String, SectionAssignment> assembleAssignments(List<String> leafKeys,
                                                                  Map<String, List<String>> leafToReqIds,
                                                                  Map<String, List<String>> leafToMandIds,
                                                                  Map<String, Integer> leafToWeight,
                                                                  CategoryMapping cm) {
        Map<String, SectionAssignment> result = new LinkedHashMap<>();
        for (String leafKey : leafKeys) {
            result.put(leafKey, new SectionAssignment(
                    leafKey, leafToReqIds.get(leafKey), leafToMandIds.get(leafKey),
                    leafToWeight.get(leafKey), cm.roleOf(leafKey)));
        }
        return result;
    }

    private void logAssignments(Map<String, SectionAssignment> result) {
        if (!log.isInfoEnabled()) return;
        result.forEach((key, a) ->
                log.info("  assignment[{}]: {} reqs, {} mands, weight={}, role={}",
                        key, a.requirementIds().size(), a.mandatoryItemIds().size(),
                        a.weight(), a.role()));
    }

    /**
     * 한 요구사항이 어느 leaf로 가야 하는지 결정 (round-robin + soft cap 적용).
     *
     * 알고리즘:
     * 1. CategoryMapping에서 req.category → 후보 leaf 목록 조회
     * 2. **round-robin으로 다음 candidate** 선택 (1:N 분산)
     * 3. 선택된 leaf의 현재 부하가 LEAF_REQ_SOFT_CAP을 넘으면 **다음 candidate으로 spillover** 시도
     * 4. 모든 candidate이 cap을 넘었으면 어쩔 수 없이 round-robin 순서대로 배정
     * 5. 매핑이 아예 없으면 null
     *
     * 효과: 같은 카테고리 내 요구사항이 자연스럽게 여러 leaf에 분산되고,
     * 한 leaf 폭주(40개+)가 발생할 확률이 낮아짐.
     */
    private String pickLeafForRequirement(Requirement req, CategoryMapping mapping, List<String> leafKeys,
                                             Map<String, List<String>> leafToReqIds,
                                             Map<String, Integer> categoryRoundRobin) {
        String category = req.category();
        if (category == null || category.isBlank()) return null;

        List<String> rawCandidates = mapping.leavesForCategory(category);
        if (rawCandidates.isEmpty()) return null;

        // 실제 leafKeys에 존재하는 candidate만 필터링 (LLM이 잘못된 leaf key를 줄 수 있음)
        List<String> candidates = new ArrayList<>();
        for (String c : rawCandidates) {
            if (leafKeys.contains(c)) candidates.add(c);
        }
        if (candidates.isEmpty()) return null;

        int counter = categoryRoundRobin.getOrDefault(category, 0);
        int n = candidates.size();

        // round-robin + soft cap: spillover까지 n번 시도
        for (int attempt = 0; attempt < n; attempt++) {
            String leaf = candidates.get((counter + attempt) % n);
            int currentLoad = leafToReqIds.get(leaf).size();
            if (currentLoad < LEAF_REQ_SOFT_CAP) {
                categoryRoundRobin.put(category, counter + attempt + 1);
                return leaf;
            }
        }

        // 모든 candidate이 soft cap 초과 — round-robin 순서대로 그냥 배정 (가장 부하 적은 게 좋지만 단순화)
        String fallback = candidates.get(counter % n);
        categoryRoundRobin.put(category, counter + 1);
        return fallback;
    }

    /** MAND 항목 배치 금지 role — 사실 기반/관리 영역에는 기술 의무 항목을 넣지 않는다 */
    private static final java.util.Set<String> MAND_EXCLUDED_ROLES = java.util.Set.of(
            "WHY", "FACTUAL", "MISC", "OPS"
    );

    /**
     * 의무 항목이 어느 leaf로 가야 하는지 결정.
     * WHAT/HOW-tech/HOW-method role을 가진 leaf에 우선 배치.
     * 가장 요구사항이 적은 candidate를 선택하여 부하 균등화.
     */
    private String pickLeafForMandatoryItem(CategoryMapping mapping,
                                             List<String> leafKeys) {
        // 우선순위 1: WHAT/HOW 역할 leaf 중 가장 부하가 적은 것
        String bestCandidate = null;
        for (String leafKey : leafKeys) {
            String role = mapping.roleOf(leafKey);
            if (role != null && !MAND_EXCLUDED_ROLES.contains(role)) {
                bestCandidate = leafKey;
                break;
            }
        }
        if (bestCandidate != null) return bestCandidate;

        // 우선순위 2: 아무 role이든 WHY/FACTUAL만 아니면
        for (String leafKey : leafKeys) {
            String role = mapping.roleOf(leafKey);
            if (!"WHY".equals(role)) {
                return leafKey;
            }
        }

        // 우선순위 3: 마지막 leaf (최후 수단)
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
