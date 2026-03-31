package com.example.rag.generation.workflow;

import com.example.rag.common.PromptLoader;
import com.example.rag.common.RagException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RequirementMapper {

    private static final Logger log = LoggerFactory.getLogger(RequirementMapper.class);
    private static final TypeReference<Map<String, List<String>>> MAPPING_TYPE = new TypeReference<>() {};
    private static final int BATCH_SIZE = 50;
    private static final int FORCE_BATCH_SIZE = 30;
    private static final int MAX_PARALLEL = 3;
    private static final int MAX_FORCE_ROUNDS = 3;

    private final ModelClientProvider modelClientProvider;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public RequirementMapper(ModelClientProvider modelClientProvider,
                              PromptLoader promptLoader,
                              ObjectMapper objectMapper) {
        this.modelClientProvider = modelClientProvider;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
    }

    public record UnmappedSectionsResult(List<OutlineNode> newSections, Map<String, List<String>> newMapping) {}

    /**
     * 미배치 요구사항을 기존 목차의 적절한 위치에 leaf node로 배치한다.
     * 새로운 top-level 섹션을 만들지 않고, 기존 목차 내에 하위 항목으로 추가.
     * 반환: newSections는 빈 리스트, mapping에 새 leaf key → 요구사항 ID 매핑.
     * outline 자체를 수정하므로, 호출 후 outline을 다시 저장해야 함.
     */
    public UnmappedSectionsResult generateSectionsForUnmapped(List<OutlineNode> outline, List<Requirement> unmapped) {
        String outlineText = flattenOutline(outline);

        StringBuilder reqText = new StringBuilder();
        for (Requirement r : unmapped) {
            reqText.append("- ").append(r.id()).append(" [").append(r.importance()).append("]: ")
                    .append(r.item()).append(" — ").append(r.description()).append("\n");
        }

        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);

        String prompt = """
                다음은 제안서 목차와 미배치 요구사항 목록입니다.
                미배치 요구사항을 **기존 목차의 적절한 위치에 새로운 리프 노드로 추가**하세요.
                새로운 대분류를 만들지 말고, 기존 목차 구조 안에 배치하세요.

                ## 현재 제안서 목차
                %s

                ## 미배치 요구사항 (%d개)
                %s

                ## 규칙
                1. 각 요구사항을 기존 목차에서 **가장 적합한 상위 항목** 아래에 새 리프 노드로 추가
                2. 새 리프 노드의 key는 상위 항목의 key를 확장 (예: 상위가 "I.1.2"이고 마지막 자식이 "I.1.2.3"이면 → "I.1.2.4")
                3. 의미적으로 유사한 요구사항은 같은 리프 노드에 그룹화
                4. 제목은 구체적이고 실질적인 내용을 담아야 합니다
                5. 모든 미배치 요구사항을 빠짐없이 배치하세요
                6. **리프 노드에만 요구사항을 매핑** — children이 있는 노드에는 매핑하지 마세요

                ## 출력 형식
                반드시 JSON만 응답. parent_key는 새 리프를 추가할 기존 목차 항목의 key입니다.
                {"new_leaves": [{"parent_key":"I.1.2", "key":"I.1.2.4", "title":"새 항목 제목", "description":"설명"}], "mapping": {"I.1.2.4": ["REQ-ID", ...]}}
                """.formatted(outlineText, unmapped.size(), reqText.toString());

        String content = client.prompt().user(prompt).call().content();

        log.info("Generated leaf nodes for {} unmapped requirements", unmapped.size());
        return parseUnmappedLeafResult(content, outline);
    }

    private UnmappedSectionsResult parseUnmappedLeafResult(String content, List<OutlineNode> outline) {
        if (content == null || content.isBlank()) return new UnmappedSectionsResult(List.of(), Map.of());
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            int objStart = json.indexOf('{');
            int objEnd = json.lastIndexOf('}');
            if (objStart >= 0 && objEnd > objStart) {
                json = json.substring(objStart, objEnd + 1);
            }
            var tree = objectMapper.readTree(json);

            Map<String, List<String>> mapping = Map.of();
            if (tree.has("mapping")) {
                mapping = objectMapper.readValue(tree.get("mapping").toString(), MAPPING_TYPE);
            }

            // new_leaves를 outline 트리에 삽입
            if (tree.has("new_leaves")) {
                for (var leaf : tree.get("new_leaves")) {
                    String parentKey = leaf.has("parent_key") ? leaf.get("parent_key").asText() : null;
                    String key = leaf.get("key").asText();
                    String title = leaf.get("title").asText();
                    String desc = leaf.has("description") ? leaf.get("description").asText() : "";

                    OutlineNode newLeaf = new OutlineNode(key, title, desc, List.of());
                    if (parentKey != null) {
                        insertLeafUnderParent(outline, parentKey, newLeaf);
                    }
                }
            }

            log.info("Parsed unmapped leaf result: {} mapping keys", mapping.size());
            // outline이 in-place로 수정됨 → newSections는 빈 리스트
            return new UnmappedSectionsResult(List.of(), mapping);
        } catch (Exception e) {
            log.warn("Failed to parse unmapped leaf result: {} | preview: {}",
                    e.getMessage(), content.substring(0, Math.min(200, content.length())));
            return new UnmappedSectionsResult(List.of(), Map.of());
        }
    }

    /**
     * outline 트리에서 parentKey에 해당하는 노드를 찾아 새 leaf를 children에 추가.
     * OutlineNode가 record이므로 부모를 새 인스턴스로 교체해야 함.
     */
    private boolean insertLeafUnderParent(List<OutlineNode> nodes, String parentKey, OutlineNode newLeaf) {
        for (int i = 0; i < nodes.size(); i++) {
            OutlineNode node = nodes.get(i);
            if (node.key().equals(parentKey)) {
                List<OutlineNode> newChildren = new ArrayList<>(node.children());
                newChildren.add(newLeaf);
                nodes.set(i, new OutlineNode(node.key(), node.title(), node.description(), newChildren));
                return true;
            }
            if (!node.children().isEmpty()) {
                // children은 불변 리스트일 수 있으므로 mutable copy 필요
                List<OutlineNode> mutableChildren = new ArrayList<>(node.children());
                if (insertLeafUnderParent(mutableChildren, parentKey, newLeaf)) {
                    nodes.set(i, new OutlineNode(node.key(), node.title(), node.description(), mutableChildren));
                    return true;
                }
            }
        }
        return false;
    }

    private UnmappedSectionsResult parseUnmappedResult(String content) {
        if (content == null || content.isBlank()) return new UnmappedSectionsResult(List.of(), Map.of());
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            int objStart = json.indexOf('{');
            int objEnd = json.lastIndexOf('}');
            if (objStart >= 0 && objEnd > objStart) {
                json = json.substring(objStart, objEnd + 1);
            }
            var tree = objectMapper.readTree(json);

            List<OutlineNode> sections = List.of();
            if (tree.has("sections")) {
                sections = objectMapper.readValue(tree.get("sections").toString(),
                        new TypeReference<List<OutlineNode>>() {});
            }

            Map<String, List<String>> mapping = Map.of();
            if (tree.has("mapping")) {
                mapping = objectMapper.readValue(tree.get("mapping").toString(), MAPPING_TYPE);
            }

            log.info("Parsed unmapped sections result: {} new sections, {} mapping keys",
                    sections.size(), mapping.size());
            return new UnmappedSectionsResult(sections, mapping);
        } catch (Exception e) {
            log.warn("Failed to parse unmapped sections result: {} | preview: {}",
                    e.getMessage(), content.substring(0, Math.min(200, content.length())));
            return new UnmappedSectionsResult(List.of(), Map.of());
        }
    }

    /**
     * 요구사항을 목차에 매핑한다.
     * 요구사항이 많으면 배치로 분할하여 병렬 매핑 후 합산.
     * 1차 매핑 후 미배치가 남으면 2차 강제 매핑을 수행.
     */
    public Map<String, List<String>> map(List<OutlineNode> outline, List<Requirement> requirements) {
        String outlineText = flattenOutline(outline);

        Map<String, List<String>> mergedMapping;
        if (requirements.size() <= BATCH_SIZE) {
            mergedMapping = new HashMap<>(mapBatch(outlineText, requirements));
        } else {
            mergedMapping = mapParallel(outlineText, requirements);
        }

        // 미배치 요구사항 수집
        java.util.Set<String> mappedIds = new java.util.HashSet<>();
        mergedMapping.values().forEach(mappedIds::addAll);
        List<Requirement> unmapped = requirements.stream()
                .filter(r -> !mappedIds.contains(r.id()))
                .toList();

        log.info("After 1st pass: {} mapped, {} unmapped out of {}",
                mappedIds.size(), unmapped.size(), requirements.size());

        // 반복 강제 매핑: 미배치가 목표(7%) 이하가 될 때까지 최대 MAX_FORCE_ROUNDS회 반복
        int targetUnmapped = (int) Math.ceil(requirements.size() * 0.07);
        for (int round = 1; round <= MAX_FORCE_ROUNDS && !unmapped.isEmpty() && unmapped.size() > targetUnmapped; round++) {
            log.info("Force-mapping round {}/{}: {} unmapped (target: ≤{})", round, MAX_FORCE_ROUNDS, unmapped.size(), targetUnmapped);
            Map<String, List<String>> forceMapped = forceMapUnmapped(outlineText, unmapped);
            for (var entry : forceMapped.entrySet()) {
                mergedMapping.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .addAll(entry.getValue());
            }

            // 미배치 재계산
            java.util.Set<String> currentMappedIds = new java.util.HashSet<>();
            mergedMapping.values().forEach(currentMappedIds::addAll);
            unmapped = requirements.stream().filter(r -> !currentMappedIds.contains(r.id())).toList();
            log.info("After force-mapping round {}: {} still unmapped out of {}", round, unmapped.size(), requirements.size());
        }

        log.info("Requirement mapping complete: {} outline keys, {} total assignments",
                mergedMapping.size(), mergedMapping.values().stream().mapToInt(List::size).sum());
        return mergedMapping;
    }

    private Map<String, List<String>> mapParallel(String outlineText, List<Requirement> requirements) {
        List<List<Requirement>> batches = splitIntoBatches(requirements);
        log.info("Splitting {} requirements into {} batches for mapping", requirements.size(), batches.size());

        Map<String, List<String>> mergedMapping = new HashMap<>();
        Object lock = new Object();
        Semaphore semaphore = new Semaphore(MAX_PARALLEL);
        AtomicInteger completed = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            List<Requirement> batch = batches.get(i);
            int totalBatches = batches.size();

            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        Map<String, List<String>> partial = mapBatch(outlineText, batch);
                        synchronized (lock) {
                            for (var entry : partial.entrySet()) {
                                mergedMapping.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                                        .addAll(entry.getValue());
                            }
                        }
                        int done = completed.incrementAndGet();
                        log.info("Mapping batch {}/{} complete: {} assignments",
                                done, totalBatches, partial.values().stream().mapToInt(List::size).sum());
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RagException("Requirement mapping interrupted", e);
                }
            }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return mergedMapping;
    }

    /**
     * 2차 강제 매핑: 미배치 요구사항을 반드시 가장 유사한 섹션에 배치.
     */
    private Map<String, List<String>> forceMapUnmapped(String outlineText, List<Requirement> unmapped) {
        List<List<Requirement>> batches = splitIntoBatches(unmapped, FORCE_BATCH_SIZE);
        Map<String, List<String>> result = new HashMap<>();
        Object lock = new Object();
        Semaphore semaphore = new Semaphore(MAX_PARALLEL);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (List<Requirement> batch : batches) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        Map<String, List<String>> partial = forceMapBatch(outlineText, batch);
                        synchronized (lock) {
                            for (var entry : partial.entrySet()) {
                                result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                                        .addAll(entry.getValue());
                            }
                        }
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RagException("Requirement mapping interrupted", e);
                }
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return result;
    }

    private Map<String, List<String>> forceMapBatch(String outlineText, List<Requirement> requirements) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);

        StringBuilder reqText = new StringBuilder();
        for (Requirement r : requirements) {
            reqText.append("- ").append(r.id()).append(": ").append(r.item()).append("\n");
        }

        String forcePrompt = """
                다음 요구사항들이 아직 목차에 배치되지 않았습니다.
                각 요구사항을 **반드시** 가장 적합한 목차 항목에 배치하세요.
                완벽히 일치하지 않더라도 **가장 유사한 항목**에 배치해야 합니다. 누락은 절대 불가합니다.

                ## 제안서 목차 (★매핑대상 = 리프 노드)
                %s

                ## 미배치 요구사항 (%d개 — 전부 배치 필수)
                %s

                ## 출력 규칙
                - ★매핑대상 표시가 있는 리프 노드 key만 사용
                - **%d개 요구사항 전부** 빠짐없이 배치 (누락 시 실패로 간주)
                - 반드시 JSON만 응답: {"목차key": ["요구사항id", ...]}
                """.formatted(outlineText, requirements.size(), reqText.toString(), requirements.size());

        String content = client.prompt().user(forcePrompt).call().content();
        Map<String, List<String>> mapping = parseMapping(content);
        int assigned = mapping.values().stream().mapToInt(List::size).sum();
        log.info("Force-map batch: {}/{} requirements assigned", assigned, requirements.size());
        return mapping;
    }

    private Map<String, List<String>> mapBatch(String outlineText, List<Requirement> requirements) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);
        String prompt = promptLoader.load("generation-map-requirements.txt");

        // 간결한 형태로 변환 (id + item만 — description 생략으로 토큰 절약)
        StringBuilder reqJson = new StringBuilder("[\n");
        for (int i = 0; i < requirements.size(); i++) {
            Requirement r = requirements.get(i);
            if (i > 0) reqJson.append(",\n");
            reqJson.append("  {\"id\":\"").append(r.id()).append("\", \"item\":\"").append(r.item().replace("\"", "'")).append("\"}");
        }
        reqJson.append("\n]");

        log.info("Mapping batch of {} requirements to outline (compact)", requirements.size());

        String content = client.prompt()
                .user(u -> u.text(prompt)
                        .param("outline", outlineText)
                        .param("requirements", reqJson))
                .call()
                .content();

        Map<String, List<String>> mapping = parseMapping(content);
        log.info("Batch mapping parsed: {} keys, {} assignments",
                mapping.size(), mapping.values().stream().mapToInt(List::size).sum());
        return mapping;
    }

    private List<List<Requirement>> splitIntoBatches(List<Requirement> requirements) {
        return splitIntoBatches(requirements, BATCH_SIZE);
    }

    private List<List<Requirement>> splitIntoBatches(List<Requirement> requirements, int batchSize) {
        List<List<Requirement>> batches = new ArrayList<>();
        for (int i = 0; i < requirements.size(); i += batchSize) {
            batches.add(requirements.subList(i, Math.min(i + batchSize, requirements.size())));
        }
        return batches;
    }

    private String flattenOutline(List<OutlineNode> nodes) {
        StringBuilder sb = new StringBuilder();
        flattenOutlineRecursive(nodes, sb, 0);
        return sb.toString().trim();
    }

    private void flattenOutlineRecursive(List<OutlineNode> nodes, StringBuilder sb, int depth) {
        for (OutlineNode node : nodes) {
            boolean isLeaf = node.children().isEmpty();
            sb.append("  ".repeat(depth)).append(node.key()).append(". ").append(node.title());
            if (isLeaf) {
                sb.append("  ★매핑대상");
            }
            sb.append("\n");
            if (!isLeaf) {
                flattenOutlineRecursive(node.children(), sb, depth + 1);
            }
        }
    }

    private Map<String, List<String>> parseMapping(String content) {
        if (content == null || content.isBlank()) return Map.of();
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            // JSON 객체 시작/끝 탐색
            int objStart = json.indexOf('{');
            int objEnd = json.lastIndexOf('}');
            if (objStart >= 0 && objEnd > objStart) {
                json = json.substring(objStart, objEnd + 1);
            }
            return objectMapper.readValue(json, MAPPING_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse requirement mapping JSON: {} | preview: {}",
                    e.getMessage(), content.substring(0, Math.min(content.length(), 300)));
            return Map.of();
        }
    }
}
