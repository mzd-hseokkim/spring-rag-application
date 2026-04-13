package kr.co.mz.ragservice.generation.workflow;

import kr.co.mz.ragservice.common.PromptLoader;
import kr.co.mz.ragservice.common.RagException;
import kr.co.mz.ragservice.generation.dto.OutlineNode;
import kr.co.mz.ragservice.model.ModelClientProvider;
import kr.co.mz.ragservice.model.ModelPurpose;
import kr.co.mz.ragservice.model.TokenRecordingContext;
import kr.co.mz.ragservice.questionnaire.workflow.Requirement;
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
     *
     * 2단계 처리:
     * 1단계 - LLM에게 각 요구사항을 어떤 상위 섹션에 넣을지만 질문 (단순 매핑)
     * 2단계 - 코드에서 그룹핑 → leaf 노드 생성 → outline 삽입 + mapping 구성
     *
     * outline 자체를 수정하므로, 호출 후 outline을 다시 저장해야 함.
     */
    public UnmappedSectionsResult generateSectionsForUnmapped(List<OutlineNode> outline, List<Requirement> unmapped) {
        String outlineText = flattenOutline(outline);

        // 요구사항 ID 목록을 명확하게 구성
        StringBuilder reqList = new StringBuilder();
        for (Requirement r : unmapped) {
            reqList.append(r.id()).append(": ").append(r.item()).append("\n");
        }

        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.GENERATION);

        // 1단계: LLM에게 요구사항 → 상위섹션 배정만 요청 (단순한 JSON)
        String prompt = """
                다음은 제안서 목차와 미배치 요구사항 목록입니다.
                각 요구사항을 가장 적합한 목차 항목에 배정하세요.

                ## 제안서 목차
                %s

                ## 미배치 요구사항 (%d개)
                %s

                ## 규칙
                1. 각 요구사항 ID를 위 목차의 key에 배정하세요
                2. 의미적으로 유사한 요구사항은 같은 목차 항목에 배정하세요
                3. 모든 요구사항을 빠짐없이 배정하세요
                4. 완벽히 일치하지 않더라도 가장 유사한 항목에 배정하세요

                ## 출력 형식 (반드시 JSON만 응답)
                {"목차key": ["요구사항id", ...], ...}
                """.formatted(outlineText, unmapped.size(), reqList.toString());

        String content = client.prompt().user(prompt).call().content();
        log.info("Unmapped assignment LLM response length: {}", content != null ? content.length() : 0);

        Map<String, List<String>> assignment = parseMapping(content);
        if (assignment.isEmpty()) {
            log.warn("LLM returned empty assignment for {} unmapped requirements", unmapped.size());
            return new UnmappedSectionsResult(List.of(), Map.of());
        }

        // 유효한 요구사항 ID만 필터링
        java.util.Set<String> validReqIds = unmapped.stream()
                .map(Requirement::id).collect(java.util.stream.Collectors.toSet());
        Map<String, Requirement> reqById = new HashMap<>();
        for (Requirement r : unmapped) reqById.put(r.id(), r);

        // 기존 outline key 수집
        java.util.Set<String> existingKeys = new java.util.HashSet<>();
        collectAllKeys(outline, existingKeys);

        // 2단계: targetKey 기준으로 그룹핑
        Map<String, List<String>> groupedByTarget = groupByTargetKey(assignment, validReqIds, existingKeys);

        // 3단계: 그룹별로 leaf 노드 생성 + outline 삽입 + mapping 구성
        Map<String, List<String>> newMapping = createLeafNodes(groupedByTarget, outline, reqById, existingKeys);

        int assignedCount = newMapping.values().stream().mapToInt(List::size).sum();
        log.info("Unmapped processing complete: {}/{} requirements assigned to {} new leaves",
                assignedCount, unmapped.size(), newMapping.size());
        return new UnmappedSectionsResult(List.of(), newMapping);
    }

    private Map<String, List<String>> groupByTargetKey(Map<String, List<String>> assignment,
                                                        java.util.Set<String> validReqIds,
                                                        java.util.Set<String> existingKeys) {
        Map<String, List<String>> groupedByTarget = new java.util.LinkedHashMap<>();
        for (var entry : assignment.entrySet()) {
            String sectionKey = entry.getKey();
            List<String> reqIds = entry.getValue().stream()
                    .filter(validReqIds::contains)
                    .toList();
            if (!reqIds.isEmpty()) {
                String targetKey = existingKeys.contains(sectionKey) ? sectionKey : findClosestParentKey(sectionKey, existingKeys);
                if (targetKey != null) {
                    groupedByTarget.computeIfAbsent(targetKey, k -> new ArrayList<>()).addAll(reqIds);
                } else {
                    log.warn("Section key '{}' not found in outline, skipping {} requirements", sectionKey, reqIds.size());
                }
            }
        }
        return groupedByTarget;
    }

    private Map<String, List<String>> createLeafNodes(Map<String, List<String>> groupedByTarget,
                                                        List<OutlineNode> outline,
                                                        Map<String, Requirement> reqById,
                                                        java.util.Set<String> existingKeys) {
        Map<String, List<String>> newMapping = new HashMap<>();
        for (var entry : groupedByTarget.entrySet()) {
            String targetKey = entry.getKey();
            List<String> reqIds = entry.getValue().stream().distinct().toList();

            String leafTitle = buildLeafTitle(reqIds, reqById);
            String leafDesc = reqIds.stream()
                    .map(id -> reqById.containsKey(id) ? reqById.get(id).item() : id)
                    .collect(java.util.stream.Collectors.joining(", "));

            String leafKey = generateNextChildKey(targetKey, existingKeys);

            OutlineNode newLeaf = new OutlineNode(leafKey, leafTitle, leafDesc, List.of());
            boolean inserted = insertLeafUnderParent(outline, targetKey, newLeaf);
            if (inserted) {
                existingKeys.add(leafKey);
                newMapping.put(leafKey, new ArrayList<>(reqIds));
                log.info("Created leaf '{}' under '{}' with {} requirements: {}",
                        leafKey, targetKey, reqIds.size(), reqIds);
            } else {
                log.warn("Failed to insert leaf under '{}' for requirements: {}", targetKey, reqIds);
            }
        }
        return newMapping;
    }

    /**
     * 요구사항 목록에서 대표적인 leaf 제목을 생성.
     */
    private String buildLeafTitle(List<String> reqIds, Map<String, Requirement> reqById) {
        if (reqIds.size() == 1) {
            Requirement r = reqById.get(reqIds.get(0));
            return r != null ? r.item() : reqIds.get(0);
        }
        // 여러 요구사항이면 첫 번째 item 기반
        Requirement first = reqById.get(reqIds.get(0));
        String base = first != null ? first.item() : reqIds.get(0);
        if (base.length() > 30) base = base.substring(0, 30);
        return base + " 외 " + (reqIds.size() - 1) + "건";
    }

    /**
     * targetKey 아래에 새 자식 key를 생성. 기존 자식 중 마지막 번호 + 1.
     */
    private String generateNextChildKey(String targetKey,
                                         java.util.Set<String> existingKeys) {
        String prefix = targetKey + ".";
        int maxNum = 0;
        for (String key : existingKeys) {
            if (key.startsWith(prefix)) {
                String suffix = key.substring(prefix.length());
                // 직접 자식만 (예: "1.2.3"의 자식 "1.2.3.X"만, "1.2.3.X.Y"는 제외)
                if (!suffix.contains(".")) {
                    try {
                        maxNum = Math.max(maxNum, Integer.parseInt(suffix));
                    } catch (NumberFormatException e) {
                        // non-numeric suffix, skip
                    }
                }
            }
        }
        return prefix + (maxNum + 1);
    }

    private void collectAllKeys(List<OutlineNode> nodes, java.util.Set<String> keys) {
        for (OutlineNode node : nodes) {
            keys.add(node.key());
            if (!node.children().isEmpty()) {
                collectAllKeys(node.children(), keys);
            }
        }
    }

    /**
     * parentKey의 접두어를 순차적으로 줄여가며 existingKeys에서 매칭되는 key를 찾는다.
     */
    private String findClosestParentKey(String parentKey, java.util.Set<String> existingKeys) {
        String candidate = parentKey;
        while (candidate.contains(".")) {
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
            if (existingKeys.contains(candidate)) {
                return candidate;
            }
        }
        // 최상위도 검색
        if (existingKeys.contains(candidate)) return candidate;
        return null;
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

        // LLM이 반환한 요구사항 ID를 실제 ID와 대조하여 유효하지 않은 것 제거
        java.util.Set<String> validIds = requirements.stream()
                .map(Requirement::id).collect(java.util.stream.Collectors.toSet());
        int invalidCount = 0;
        for (var entry : mergedMapping.entrySet()) {
            List<String> ids = entry.getValue();
            int before = ids.size();
            ids.removeIf(id -> !validIds.contains(id));
            invalidCount += before - ids.size();
        }
        // 빈 리스트가 된 key 제거
        mergedMapping.entrySet().removeIf(e -> e.getValue().isEmpty());
        if (invalidCount > 0) {
            log.warn("Removed {} invalid requirement IDs from mapping (LLM returned non-existent IDs)", invalidCount);
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

            futures.add(CompletableFuture.runAsync(TokenRecordingContext.wrap(() -> {
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
            })));
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
            futures.add(CompletableFuture.runAsync(TokenRecordingContext.wrap(() -> {
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
            })));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return result;
    }

    private Map<String, List<String>> forceMapBatch(String outlineText, List<Requirement> requirements) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.GENERATION);

        StringBuilder reqText = new StringBuilder();
        for (Requirement r : requirements) {
            reqText.append("- ").append(r.id()).append(": ").append(r.item());
            if (r.description() != null && !r.description().isBlank()) {
                reqText.append(" — ").append(r.description());
            }
            reqText.append("\n");
        }

        String forcePrompt = """
                다음 요구사항들이 아직 목차에 배치되지 않았습니다.
                각 요구사항을 **반드시** 가장 적합한 목차 항목에 배치하세요.
                완벽히 일치하지 않더라도 **가장 유사한 항목**에 배치해야 합니다. 누락은 절대 불가합니다.
                요구사항이 일반적/포괄적이면 해당 주제를 다루는 리프 노드에 배치하세요. [상위경로]를 참고하면 리프의 맥락을 알 수 있습니다.

                ## 제안서 목차 (★매핑대상 = 리프 노드)
                %s

                ## 미배치 요구사항 (%d개 — 전부 배치 필수)
                %s

                ## 출력 규칙
                - ★매핑대상 표시가 있는 리프 노드 key만 사용
                - 각 요구사항은 관련된 여러 리프에 배치 가능 (상위 항목 맥락이 다르면 양쪽 모두 배치)
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
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.GENERATION);
        String prompt = promptLoader.load("generation-map-requirements.txt");

        StringBuilder reqJson = new StringBuilder("[\n");
        for (int i = 0; i < requirements.size(); i++) {
            Requirement r = requirements.get(i);
            if (i > 0) reqJson.append(",\n");
            reqJson.append("  {\"id\":\"").append(r.id())
                    .append("\", \"item\":\"").append(r.item().replace("\"", "'"))
                    .append("\", \"desc\":\"").append(
                            r.description() != null ? r.description().replace("\"", "'") : "")
                    .append("\"}");
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
        flattenOutlineRecursive(nodes, sb, 0, "");
        return sb.toString().trim();
    }

    private void flattenOutlineRecursive(List<OutlineNode> nodes, StringBuilder sb, int depth, String parentPath) {
        for (OutlineNode node : nodes) {
            boolean isLeaf = node.children().isEmpty();
            sb.append("  ".repeat(depth)).append(node.key()).append(". ").append(node.title());
            if (isLeaf) {
                sb.append("  ★매핑대상");
                if (!parentPath.isEmpty()) {
                    sb.append(" [").append(parentPath).append("]");
                }
            }
            sb.append("\n");
            if (!isLeaf) {
                String childPath = parentPath.isEmpty() ? node.title() : parentPath + " > " + node.title();
                flattenOutlineRecursive(node.children(), sb, depth + 1, childPath);
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
