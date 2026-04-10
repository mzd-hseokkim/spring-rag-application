package com.example.rag.generation.workflow;

import com.example.rag.common.PromptLoader;
import com.example.rag.generation.dto.OutlineNode;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import com.example.rag.questionnaire.workflow.Requirement;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outline leaf 목록과 요구사항 카테고리 목록을 받아, 1회성 LLM 호출로 **카테고리→leaf 매핑**을 생성.
 *
 * 이 매핑은 RuleBasedPlanner가 결정론으로 사용한다. LLM은 "어느 카테고리가 어느 leaf에 가야 하는지"만
 * 결정하고, 그 후로는 코드가 결정론으로 요구사항을 분배.
 *
 * LLM 호출 실패 시 빈 매핑 반환 → Planner가 heuristic fallback으로 동작.
 */
@Service
public class CategoryMappingDeriver {

    private static final Logger log = LoggerFactory.getLogger(CategoryMappingDeriver.class);

    private final ModelClientProvider modelClientProvider;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public CategoryMappingDeriver(ModelClientProvider modelClientProvider,
                                    PromptLoader promptLoader,
                                    ObjectMapper objectMapper) {
        this.modelClientProvider = modelClientProvider;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
    }

    /**
     * outline의 leaf와 requirements의 카테고리에서 매핑을 생성.
     *
     * @param outlineLeaves leaf 노드 목록 (expanded 전의 원본 leaf)
     * @param requirements  요구사항 목록 — 여기서 unique 카테고리 추출
     * @return CategoryMapping (실패 시 empty)
     */
    public CategoryMapping derive(List<LeafDescriptor> outlineLeaves, List<Requirement> requirements) {
        if (outlineLeaves == null || outlineLeaves.isEmpty()) {
            return CategoryMapping.empty();
        }
        // 카테고리별 항목 수 계산 — LLM이 1:N 분배 결정 시 사용
        java.util.LinkedHashMap<String, Integer> categoryCounts = computeCategoryCounts(requirements);
        if (categoryCounts.isEmpty()) {
            log.info("CategoryMappingDeriver: no categories in requirements, skipping");
            return CategoryMapping.empty();
        }

        StringBuilder leavesBuf = new StringBuilder();
        for (LeafDescriptor leaf : outlineLeaves) {
            leavesBuf.append("- key=\"").append(leaf.key()).append("\"")
                    .append(", title=\"").append(leaf.title()).append("\"");
            if (leaf.parentPath() != null && !leaf.parentPath().isBlank()) {
                leavesBuf.append(", path=\"").append(leaf.parentPath()).append("\"");
            }
            leavesBuf.append("\n");
        }

        // 카테고리: 항목 수와 함께 표시 — LLM이 큰 카테고리를 1:N 분산하도록 유도
        StringBuilder categoriesBuf = new StringBuilder();
        for (var entry : categoryCounts.entrySet()) {
            categoriesBuf.append("- ").append(entry.getKey())
                    .append(": ").append(entry.getValue()).append("개\n");
        }
        Set<String> categories = categoryCounts.keySet();

        final String leavesParam = leavesBuf.toString();
        final String categoriesParam = categoriesBuf.toString();
        String prompt = promptLoader.load("generation-derive-category-mapping.txt");

        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.GENERATION);
        String content;
        try {
            content = client.prompt()
                    .user(u -> u.text(prompt)
                            .param("leaves", leavesParam)
                            .param("categories", categoriesParam))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("CategoryMappingDeriver LLM call failed: {}", e.getMessage());
            return CategoryMapping.empty();
        }

        CategoryMapping mapping = parseMapping(content);
        logMapping(mapping, outlineLeaves, categories);
        return mapping;
    }

    /**
     * 카테고리별 항목 수를 계산. 결과는 항목 수 내림차순 정렬 (큰 카테고리가 위에).
     * LinkedHashMap으로 반환하여 순서 유지.
     */
    private java.util.LinkedHashMap<String, Integer> computeCategoryCounts(List<Requirement> requirements) {
        if (requirements == null) return new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> raw = new java.util.HashMap<>();
        for (Requirement r : requirements) {
            String c = r.category();
            if (c == null || c.isBlank()) continue;
            raw.merge(c, 1, Integer::sum);
        }
        // 항목 수 내림차순 정렬
        return raw.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        java.util.Map.Entry::getValue,
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));
    }

    private CategoryMapping parseMapping(String content) {
        if (content == null || content.isBlank()) {
            log.warn("CategoryMappingDeriver returned empty response");
            return CategoryMapping.empty();
        }
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            int objStart = json.indexOf('{');
            int objEnd = json.lastIndexOf('}');
            if (objStart < 0 || objEnd <= objStart) {
                log.warn("CategoryMappingDeriver response is not JSON object: {}",
                        content.substring(0, Math.min(content.length(), 200)));
                return CategoryMapping.empty();
            }
            String jsonCandidate = json.substring(objStart, objEnd + 1);
            ParsedResponse parsed = objectMapper.readValue(jsonCandidate, ParsedResponse.class);

            Map<String, List<String>> categoryToLeafKeys = parsed.categoryToLeafKeys() != null
                    ? new LinkedHashMap<>(parsed.categoryToLeafKeys())
                    : Map.of();
            Map<String, String> leafKeyToRole = parsed.leafKeyToRole() != null
                    ? new LinkedHashMap<>(parsed.leafKeyToRole())
                    : Map.of();

            return new CategoryMapping(categoryToLeafKeys, leafKeyToRole);
        } catch (Exception e) {
            log.warn("Failed to parse CategoryMapping: {} | preview: {}",
                    e.getMessage(), content.substring(0, Math.min(content.length(), 300)));
            return CategoryMapping.empty();
        }
    }

    private void logMapping(CategoryMapping mapping, List<LeafDescriptor> leaves, Set<String> categories) {
        if (mapping.isEmpty()) {
            log.warn("CategoryMapping: empty (fallback to heuristic in planner)");
            return;
        }
        log.info("CategoryMapping: {} categories mapped, {} leaves with roles",
                mapping.categoryToLeafKeys().size(),
                mapping.leafKeyToRole() != null ? mapping.leafKeyToRole().size() : 0);
        mapping.categoryToLeafKeys().forEach((cat, leafKeys) ->
                log.info("  {} → {}", cat, leafKeys));
        if (mapping.leafKeyToRole() != null) {
            mapping.leafKeyToRole().forEach((leafKey, role) ->
                    log.info("  role[{}] = {}", leafKey, role));
        }

        // 누락 카테고리 진단
        List<String> unmapped = new ArrayList<>();
        for (String cat : categories) {
            if (!mapping.categoryToLeafKeys().containsKey(cat)) {
                unmapped.add(cat);
            }
        }
        if (!unmapped.isEmpty()) {
            log.warn("CategoryMapping: {} categories unmapped: {}", unmapped.size(), unmapped);
        }
    }

    /**
     * outline의 leaf를 나타내는 간단한 descriptor. CategoryMappingDeriver 입력용.
     */
    public record LeafDescriptor(String key, String title, String parentPath) {}

    /**
     * LLM 응답 파싱용 내부 record.
     */
    private record ParsedResponse(
            Map<String, List<String>> categoryToLeafKeys,
            Map<String, String> leafKeyToRole
    ) {}

    /**
     * OutlineNode 리스트에서 leaf를 수집하여 LeafDescriptor로 변환.
     * CategoryMappingDeriver의 입력으로 사용.
     */
    public static List<LeafDescriptor> collectLeafDescriptors(List<OutlineNode> outline) {
        List<LeafDescriptor> result = new ArrayList<>();
        collectRecursive(outline, "", result);
        return result;
    }

    private static void collectRecursive(List<OutlineNode> nodes, String parentPath, List<LeafDescriptor> result) {
        for (OutlineNode node : nodes) {
            if (node.children().isEmpty()) {
                result.add(new LeafDescriptor(node.key(), node.title(), parentPath));
            } else {
                String childPath = parentPath.isEmpty() ? node.title() : parentPath + " > " + node.title();
                collectRecursive(node.children(), childPath, result);
            }
        }
    }
}
