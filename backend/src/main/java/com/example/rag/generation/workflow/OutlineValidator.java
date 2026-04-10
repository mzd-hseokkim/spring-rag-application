package com.example.rag.generation.workflow;

import com.example.rag.generation.dto.OutlineNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outline 결과에 대한 결정론 검증.
 *
 * 5가지 규칙을 적용한다:
 * 1. MIN_CHILDREN — 모든 expanded leaf에 최소 1개 child
 * 2. REQ_COVERAGE — 모든 추출 요구사항이 어떤 leaf의 topics에 등장
 * 3. REQ_UNIQUENESS — 동일 REQ-ID가 3개 이상 leaf에 중복되지 않음
 * 4. MANDATORY_SLOTS — 모든 mandatory item이 어떤 leaf에 배치
 * 5. WEIGHT_DISTRIBUTION — leaf의 children 수가 weight 비율과 ±20% 이내 (warning only)
 *
 * 이 validator는 LLM을 사용하지 않는다. RFP 종류와 무관하게 동일하게 작동.
 */
@Service
public class OutlineValidator {

    private static final Logger log = LoggerFactory.getLogger(OutlineValidator.class);
    private static final Pattern REQ_ID_PATTERN = Pattern.compile("([A-Z]{2,5}-\\d+)");
    private static final int MAX_LEAVES_PER_REQ = 2; // 3개 이상이면 위반

    /**
     * Validation 컨텍스트 — RFP에서 추출된 정보를 한 번에 전달하기 위한 묶음.
     */
    public record Context(
            Set<String> allRequirementIds,
            List<MandatoryItem> mandatoryItems,
            Map<String, Integer> evaluationWeights,
            Integer totalScore
    ) {
        public static Context fromRfpMandates(Set<String> reqIds, RfpMandates mandates) {
            return new Context(
                    reqIds != null ? reqIds : Set.of(),
                    mandates != null ? mandates.mandatoryItems() : List.of(),
                    mandates != null ? mandates.evaluationWeights() : Map.of(),
                    mandates != null ? mandates.totalScore() : null);
        }
    }

    /**
     * 전체 검증 — 5개 규칙 모두 적용.
     */
    public ValidationResult validate(List<OutlineNode> outline, Context ctx) {
        if (outline == null) outline = List.of();
        List<ValidationViolation> all = new ArrayList<>();
        all.addAll(validateMinimumChildren(outline));
        all.addAll(validateRequirementCoverage(outline, ctx.allRequirementIds()));
        all.addAll(validateRequirementUniqueness(outline));
        all.addAll(validateMandatorySlots(outline, ctx.mandatoryItems()));
        all.addAll(validateWeightDistribution(outline, ctx.evaluationWeights(), ctx.totalScore()));

        ValidationResult result = ValidationResult.of(all);
        logResult(result);
        return result;
    }

    /**
     * Rule 1: MIN_CHILDREN — depth ≤ 2인 노드(L1, L2)는 children이 비어 있으면 안 됨.
     * 빈 섹션 버그(이번 cleanup에서 발견된 II.1, III.3 등)를 차단한다.
     */
    public List<ValidationViolation> validateMinimumChildren(List<OutlineNode> outline) {
        List<ValidationViolation> violations = new ArrayList<>();
        walkForMinChildren(outline, 1, violations);
        return violations;
    }

    private void walkForMinChildren(List<OutlineNode> nodes, int depth, List<ValidationViolation> violations) {
        for (OutlineNode node : nodes) {
            if (depth <= 2 && node.children().isEmpty()) {
                violations.add(ValidationViolation.error(
                        "MIN_CHILDREN",
                        "Section '" + node.title() + "' (depth=" + depth + ") has no children",
                        node.key()));
            }
            if (!node.children().isEmpty()) {
                walkForMinChildren(node.children(), depth + 1, violations);
            }
        }
    }

    /**
     * Rule 2: REQ_COVERAGE — 입력된 모든 REQ-ID가 outline의 어떤 노드 (title 또는 description)에 등장.
     */
    public List<ValidationViolation> validateRequirementCoverage(List<OutlineNode> outline, Set<String> allReqIds) {
        if (allReqIds == null || allReqIds.isEmpty()) return List.of();
        Set<String> foundIds = collectAllReqIdsInOutline(outline);
        List<ValidationViolation> violations = new ArrayList<>();
        for (String reqId : allReqIds) {
            if (!foundIds.contains(reqId)) {
                violations.add(ValidationViolation.warning(
                        "REQ_COVERAGE",
                        "Requirement " + reqId + " not found in any outline node",
                        null));
            }
        }
        return violations;
    }

    /**
     * Rule 3: REQ_UNIQUENESS — 동일 REQ-ID가 너무 많은 leaf에 등장하면 안 됨 (3개 이상 = 위반).
     */
    public List<ValidationViolation> validateRequirementUniqueness(List<OutlineNode> outline) {
        Map<String, Set<String>> reqToLeaves = new HashMap<>();
        collectReqLeafMap(outline, "", reqToLeaves);

        List<ValidationViolation> violations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : reqToLeaves.entrySet()) {
            int count = entry.getValue().size();
            if (count > MAX_LEAVES_PER_REQ) {
                violations.add(ValidationViolation.warning(
                        "REQ_UNIQUENESS",
                        "REQ-ID " + entry.getKey() + " appears in " + count + " leaves: " + entry.getValue(),
                        null));
            }
        }
        return violations;
    }

    private void collectReqLeafMap(List<OutlineNode> nodes, String parentPath, Map<String, Set<String>> map) {
        for (OutlineNode node : nodes) {
            String fullPath = parentPath.isEmpty() ? node.key() : parentPath + "/" + node.key();
            String text = (node.title() != null ? node.title() : "") + " " +
                          (node.description() != null ? node.description() : "");
            Matcher m = REQ_ID_PATTERN.matcher(text);
            while (m.find()) {
                String id = m.group(1);
                map.computeIfAbsent(id, k -> new HashSet<>()).add(fullPath);
            }
            if (!node.children().isEmpty()) {
                collectReqLeafMap(node.children(), fullPath, map);
            }
        }
    }

    /**
     * Rule 4: MANDATORY_SLOTS — 모든 mandatory item이 outline의 어떤 노드에서 다뤄져야 함.
     * 여기서는 "다뤄짐"의 기준을 mandatoryItem.title의 핵심 키워드가 outline 어디엔가 등장하는지로 본다.
     */
    public List<ValidationViolation> validateMandatorySlots(List<OutlineNode> outline, List<MandatoryItem> items) {
        if (items == null || items.isEmpty()) return List.of();
        String allText = collectAllText(outline).toLowerCase();
        List<ValidationViolation> violations = new ArrayList<>();
        for (MandatoryItem item : items) {
            if (item.title() == null || item.title().isBlank()) continue;
            // 간단한 keyword presence: title의 첫 명사구(공백/조사 분리) 중 하나라도 있으면 통과
            String[] tokens = item.title().split("[\\s·,()/]+");
            boolean found = false;
            for (String token : tokens) {
                String t = token.trim().toLowerCase();
                if (t.length() >= 2 && allText.contains(t)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                violations.add(ValidationViolation.warning(
                        "MANDATORY_SLOTS",
                        "Mandatory item " + item.id() + " ('" + item.title() + "') not found in outline",
                        null));
            }
        }
        return violations;
    }

    /**
     * Rule 5: WEIGHT_DISTRIBUTION — leaf의 children 수가 weight 비율에서 ±20% 이내 (warning only).
     * weight 매핑이 leaf에 직접 안 붙어있어서 이 룰은 outline 전체 children 수와 totalScore로 sanity-check만 한다.
     */
    public List<ValidationViolation> validateWeightDistribution(List<OutlineNode> outline,
                                                                   Map<String, Integer> weights,
                                                                   Integer totalScore) {
        if (weights == null || weights.isEmpty() || totalScore == null || totalScore <= 0) {
            return List.of();
        }
        // leaf 수 합산
        int totalLeaves = countLeaves(outline);
        if (totalLeaves == 0) return List.of();

        // 평균 children/score 비율
        double leavesPerScore = totalLeaves * 1.0 / totalScore;
        log.debug("Weight distribution sanity: {} leaves / {} totalScore = {} leaves/point",
                totalLeaves, totalScore, leavesPerScore);

        // 현재는 sanity-check만 — 구체적 leaf-weight 매핑이 없는 상태에서는 정밀 검증 불가
        // Phase B에서 SectionAssignment가 도입되면 leaf별 정밀 검증 가능
        return List.of();
    }

    // ── 헬퍼 ──

    private Set<String> collectAllReqIdsInOutline(List<OutlineNode> outline) {
        Set<String> ids = new HashSet<>();
        walkCollectIds(outline, ids);
        return ids;
    }

    private void walkCollectIds(List<OutlineNode> nodes, Set<String> ids) {
        for (OutlineNode node : nodes) {
            String text = (node.title() != null ? node.title() : "") + " " +
                          (node.description() != null ? node.description() : "");
            Matcher m = REQ_ID_PATTERN.matcher(text);
            while (m.find()) {
                ids.add(m.group(1));
            }
            if (!node.children().isEmpty()) {
                walkCollectIds(node.children(), ids);
            }
        }
    }

    private String collectAllText(List<OutlineNode> outline) {
        StringBuilder sb = new StringBuilder();
        walkCollectText(outline, sb);
        return sb.toString();
    }

    private void walkCollectText(List<OutlineNode> nodes, StringBuilder sb) {
        for (OutlineNode node : nodes) {
            if (node.title() != null) sb.append(node.title()).append(' ');
            if (node.description() != null) sb.append(node.description()).append(' ');
            if (!node.children().isEmpty()) {
                walkCollectText(node.children(), sb);
            }
        }
    }

    private int countLeaves(List<OutlineNode> nodes) {
        int count = 0;
        for (OutlineNode node : nodes) {
            if (node.children().isEmpty()) {
                count++;
            } else {
                count += countLeaves(node.children());
            }
        }
        return count;
    }

    private void logResult(ValidationResult result) {
        if (result.passed() && result.warnings().isEmpty()) {
            log.info("Outline validation: PASSED (no violations)");
            return;
        }
        int errors = result.errors().size();
        int warnings = result.warnings().size();
        if (result.passed()) {
            log.info("Outline validation: PASSED with {} warnings", warnings);
        } else {
            log.warn("Outline validation: FAILED with {} errors, {} warnings", errors, warnings);
        }
        for (ValidationViolation v : result.violations()) {
            String prefix = v.isError() ? "  [ERROR]" : "  [WARN]";
            String location = v.leafKey() != null ? " (" + v.leafKey() + ")" : "";
            if (v.isError()) {
                log.warn("{} {}: {}{}", prefix, v.ruleName(), v.message(), location);
            } else {
                log.info("{} {}: {}{}", prefix, v.ruleName(), v.message(), location);
            }
        }
    }
}
