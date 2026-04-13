package kr.co.mz.ragservice.generation.workflow;

import kr.co.mz.ragservice.generation.dto.OutlineNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class OutlineUtils {

    private OutlineUtils() {}

    /** "1.2.10" 같은 계층 번호를 자연수 순서로 비교 */
    public static int compareKeys(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length ? parseSegment(pa[i]) : -1;
            int nb = i < pb.length ? parseSegment(pb[i]) : -1;
            if (na != nb) return Integer.compare(na, nb);
        }
        return 0;
    }

    private static int parseSegment(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }

    public static void collectLeafSections(List<OutlineNode> nodes, Map<String, List<String>> mapping,
                                           List<LeafSection> result) {
        collectLeafSections(nodes, mapping, result, List.of(), "");
    }

    public static void collectLeafSections(List<OutlineNode> nodes, Map<String, List<String>> mapping,
                                           List<LeafSection> result, List<String> inheritedReqIds,
                                           String parentPath) {
        for (OutlineNode node : nodes) {
            List<String> ownReqIds = mapping.getOrDefault(node.key(), List.of());
            List<String> combined = new ArrayList<>(inheritedReqIds);
            combined.addAll(ownReqIds);

            if (node.children().isEmpty()) {
                result.add(new LeafSection(node.key(), node.title(), node.description(), combined, parentPath));
            } else {
                String childPath = parentPath.isEmpty() ? node.title() : parentPath + " > " + node.title();
                collectLeafSections(node.children(), mapping, result, combined, childPath);
            }
        }
    }

    public static void flattenOutlineNodes(List<OutlineNode> nodes, List<SectionPlan> result) {
        for (OutlineNode node : nodes) {
            result.add(new SectionPlan(node.key(), node.title(), node.description(), List.of(), 0));
            if (!node.children().isEmpty()) {
                flattenOutlineNodes(node.children(), result);
            }
        }
    }

    public static List<SectionPlan> buildSectionPlans(List<OutlineNode> nodes) {
        List<SectionPlan> plans = new ArrayList<>();
        for (OutlineNode node : nodes) {
            if (node.children().isEmpty()) {
                plans.add(new SectionPlan(node.key(), node.title(), node.description(), List.of(), 500));
            } else {
                for (OutlineNode child : node.children()) {
                    plans.add(new SectionPlan(child.key(), child.title(), child.description(), List.of(), 500));
                }
            }
        }
        return plans;
    }
}
