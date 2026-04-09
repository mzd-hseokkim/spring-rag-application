package com.example.rag.generation.workflow;

import com.example.rag.common.PromptLoader;
import com.example.rag.common.RagException;
import com.example.rag.document.DocumentChunk;
import com.example.rag.document.DocumentChunkRepository;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

@Service
public class OutlineExtractor {

    private static final Logger log = LoggerFactory.getLogger(OutlineExtractor.class);
    private static final TypeReference<List<OutlineNode>> OUTLINE_LIST_TYPE = new TypeReference<>() {};
    private static final int REQ_SUMMARY_CHAR_LIMIT = 12_000;
    private static final int MAX_PARALLEL = 3;
    private static final String TRUNCATION_SUFFIX = "\n... (이하 생략)";
    private static final String CHUNK_SEPARATOR = "\n---\n";
    private static final int EXPAND_WINDOW = 3;
    private static final List<String> RECOMMENDED_OUTLINE_KEYWORDS = List.of(
            "권장 목차", "추천 목차", "제안 목차", "제안서 목차", "목차 구성", "목차(안)", "목차안",
            "제안서 구성", "작성 목차", "목차 기준", "목차 예시", "제안서 작성 목차"
    );

    private final ModelClientProvider modelClientProvider;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final DocumentChunkRepository chunkRepository;

    public OutlineExtractor(ModelClientProvider modelClientProvider,
                             PromptLoader promptLoader,
                             ObjectMapper objectMapper,
                             DocumentChunkRepository chunkRepository) {
        this.modelClientProvider = modelClientProvider;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
        this.chunkRepository = chunkRepository;
    }

    /**
     * 고객 문서에서 발주처 권장 목차를 탐색하여 LLM으로 JSON 추출.
     * DB에서 모든 청크를 순회하여 키워드 포함 위치를 전부 찾고, 각 위치마다 앞뒤로 확장.
     * 없으면 null 반환.
     */
    public String detectRecommendedOutline(List<UUID> customerDocIds) {
        List<String> relevantChunks = collectRelevantChunks(customerDocIds);

        if (relevantChunks.isEmpty()) {
            log.info("No recommended outline keywords found in customer document");
            return null;
        }

        String combinedContent = String.join(CHUNK_SEPARATOR, relevantChunks);
        if (combinedContent.length() > 10_000) {
            combinedContent = combinedContent.substring(0, 10_000) + TRUNCATION_SUFFIX;
        }

        String result = callDetectionLlm(combinedContent);

        if (result == null || result.isBlank() || result.contains("없음")) {
            log.info("No recommended outline found in document");
            return null;
        }

        return validateOutlineJson(result);
    }

    private List<String> collectRelevantChunks(List<UUID> customerDocIds) {
        List<String> relevantChunks = new ArrayList<>();
        for (UUID docId : customerDocIds) {
            collectChunksForDocument(docId, relevantChunks);
        }
        return relevantChunks;
    }

    private void collectChunksForDocument(UUID docId, List<String> relevantChunks) {
        List<DocumentChunk> allChunks = chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
        List<Integer> matchIndices = findKeywordMatchIndices(allChunks);
        if (matchIndices.isEmpty()) return;

        java.util.Set<Integer> collectedIndices = new java.util.HashSet<>();
        expandAroundMatches(allChunks, matchIndices, collectedIndices, relevantChunks);
        collectTableChunks(allChunks, collectedIndices, relevantChunks);

        log.info("Doc {} - {} keyword matches, {} chunks collected (incl. tables)",
                docId, matchIndices.size(), collectedIndices.size());
    }

    private List<Integer> findKeywordMatchIndices(List<DocumentChunk> allChunks) {
        List<Integer> matchIndices = new ArrayList<>();
        for (DocumentChunk chunk : allChunks) {
            if (RECOMMENDED_OUTLINE_KEYWORDS.stream().anyMatch(chunk.getContent()::contains)) {
                matchIndices.add(chunk.getChunkIndex());
            }
        }
        return matchIndices;
    }

    private void expandAroundMatches(List<DocumentChunk> allChunks, List<Integer> matchIndices,
                                      java.util.Set<Integer> collectedIndices, List<String> relevantChunks) {
        for (int matchIdx : matchIndices) {
            int fromIdx = Math.max(0, matchIdx - EXPAND_WINDOW);
            int toIdx = matchIdx + EXPAND_WINDOW;
            for (DocumentChunk chunk : allChunks) {
                int idx = chunk.getChunkIndex();
                if (idx >= fromIdx && idx <= toIdx && collectedIndices.add(idx)) {
                    relevantChunks.add(chunk.getContent());
                }
            }
        }
    }

    private void collectTableChunks(List<DocumentChunk> allChunks,
                                     java.util.Set<Integer> collectedIndices, List<String> relevantChunks) {
        for (DocumentChunk chunk : allChunks) {
            if (!collectedIndices.contains(chunk.getChunkIndex())
                    && chunk.getContent().trim().startsWith("|")) {
                relevantChunks.add(chunk.getContent());
                collectedIndices.add(chunk.getChunkIndex());
            }
        }
    }

    private String callDetectionLlm(String combinedContent) {
        String detectionPrompt = """
                다음은 고객 문서(RFP/제안요청서)의 일부입니다.
                발주처가 제안사에게 따르도록 권장하는 "권장 목차" 또는 "제안서 목차 구성"이 있는지 확인하세요.

                있다면: 문서에 나타난 모든 계층(대분류, 중분류 등)을 그대로 추출하여 아래 JSON 형식으로 출력하세요.
                - 대분류 key: "1", "2", "3" ...
                - 중분류 key: "1.1", "1.2", "2.1" ...
                - 소분류 key: "1.1.1", "1.1.2" ...
                - 문서에 있는 항목 제목을 그대로 사용하세요. 임의로 추가하거나 변경하지 마세요.
                없다면: "없음"이라고만 답하세요.

                출력 형식 예시:
                [{"key":"1","title":"일반현황","description":"","children":[{"key":"1.1","title":"제안사 일반현황","description":"","children":[]},{"key":"1.2","title":"제안사의 조직 및 인원","description":"","children":[]}]}]

                ## 문서 내용
                %s
                """.formatted(combinedContent);

        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.GENERATION);
        String result = client.prompt().user(detectionPrompt).call().content();
        log.info("detectRecommendedOutline raw response: {}", result);
        return result;
    }

    private String validateOutlineJson(String result) {
        String trimmed = result.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
        }
        int arrStart = trimmed.indexOf('[');
        int arrEnd = trimmed.lastIndexOf(']');
        if (arrStart < 0 || arrEnd <= arrStart) {
            if (log.isWarnEnabled()) {
                log.warn("Recommended outline response is not JSON array, discarding: {}",
                        trimmed.substring(0, Math.min(trimmed.length(), 200)));
            }
            return null;
        }
        try {
            String jsonCandidate = trimmed.substring(arrStart, arrEnd + 1);
            objectMapper.readValue(jsonCandidate, OUTLINE_LIST_TYPE);
            log.info("Detected recommended outline JSON: {}chars", jsonCandidate.length());
            return jsonCandidate;
        } catch (Exception e) {
            log.warn("Recommended outline response failed JSON validation: {}", e.getMessage());
            return null;
        }
    }


    public List<OutlineNode> extract(List<String> customerChunks, String userInput) {
        return extract(customerChunks, userInput, "", List.of(), null, null);
    }

    public List<OutlineNode> extract(List<String> customerChunks, String userInput, String requirementsSummary) {
        return extract(customerChunks, userInput, requirementsSummary, List.of(), null, null);
    }

    /**
     * 요구사항 목록이 프롬프트에 들어갈 수 없을 만큼 크면 map-reduce로 처리.
     * Map: 요구사항 배치별로 "이 요구사항을 반영할 목차 섹션을 제안하라"
     * Reduce: 모든 제안 + 문서 내용 → 최종 통합 목차
     */
    public List<OutlineNode> extract(List<String> customerChunks, String userInput,
                                      String requirementsSummary, List<Requirement> requirements,
                                      ProgressCallback progressCallback) {
        return extract(customerChunks, userInput, requirementsSummary, requirements, progressCallback, null, RfpMandates.empty());
    }

    public List<OutlineNode> extract(List<String> customerChunks, String userInput,
                                      String requirementsSummary, List<Requirement> requirements,
                                      ProgressCallback progressCallback, String recommendedOutline) {
        return extract(customerChunks, userInput, requirementsSummary, requirements, progressCallback, recommendedOutline, RfpMandates.empty());
    }

    public List<OutlineNode> extract(List<String> customerChunks, String userInput,
                                      String requirementsSummary, List<Requirement> requirements,
                                      ProgressCallback progressCallback, String recommendedOutline,
                                      RfpMandates rfpMandates) {

        RfpMandates mandates = rfpMandates != null ? rfpMandates : RfpMandates.empty();

        // 권장 목차가 있으면: 상위 구조를 코드로 고정 → LLM은 하위만 채움
        if (recommendedOutline != null) {
            return extractWithFixedTopLevel(recommendedOutline, requirementsSummary, progressCallback, mandates);
        }

        String reqs = requirementsSummary != null ? requirementsSummary : "";

        // 요구사항이 프롬프트에 직접 들어갈 수 있는 크기면 단일 호출
        if (reqs.length() <= REQ_SUMMARY_CHAR_LIMIT) {
            return extractDirect(customerChunks, userInput, reqs);
        }

        // Map-Reduce: 요구사항이 너무 많은 경우
        log.info("Requirements summary too large ({}chars), using map-reduce", reqs.length());
        if (progressCallback != null) {
            progressCallback.onProgress("요구사항이 많아 분할 처리합니다...");
        }

        // Map: 요구사항을 배치로 나눠 각각 목차 제안을 받음
        List<List<Requirement>> batches = splitRequirements(requirements, REQ_SUMMARY_CHAR_LIMIT);
        log.info("Split {} requirements into {} batches for outline map-reduce", requirements.size(), batches.size());

        List<String> allSuggestions = java.util.Collections.synchronizedList(new ArrayList<>());
        Semaphore semaphore = new Semaphore(MAX_PARALLEL);
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            List<Requirement> batch = batches.get(i);
            int batchNum = i + 1;
            int totalBatches = batches.size();

            String batchReqText = batch.stream()
                    .map(r -> "- [" + r.id() + "] [" + r.importance() + "] " + r.item() + ": " + r.description())
                    .collect(java.util.stream.Collectors.joining("\n"));

            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        String suggestion = mapOutlineSuggestion(batchReqText, batchNum, totalBatches);
                        allSuggestions.add(suggestion);
                        int done = completed.incrementAndGet();
                        log.info("Outline map batch {}/{} complete", done, totalBatches);
                        if (progressCallback != null) {
                            progressCallback.onProgress("목차 구성 중... (" + done + "/" + totalBatches + " 배치 완료)");
                        }
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RagException("Outline map interrupted", e);
                }
            }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Reduce: 모든 제안을 합쳐서 최종 목차 생성
        if (progressCallback != null) {
            progressCallback.onProgress("목차를 통합하고 있습니다...");
        }
        String combinedSuggestions = String.join("\n\n---\n\n", allSuggestions);
        return reduceOutline(customerChunks, userInput, combinedSuggestions, mandates);
    }

    /**
     * 권장 목차가 있을 때: 문서에서 추출한 구조(1-2depth)를 고정하고,
     * leaf 노드(children이 없는 노드)에 하위 목차를 LLM으로 생성.
     */
    private List<OutlineNode> extractWithFixedTopLevel(String recommendedOutline,
                                                        String requirementsSummary,
                                                        ProgressCallback progressCallback,
                                                        RfpMandates rfpMandates) {
        List<OutlineNode> topLevel = parseOutline(recommendedOutline);
        if (topLevel.isEmpty()) {
            log.warn("Failed to parse recommended outline JSON into nodes: {}", recommendedOutline);
            return List.of();
        }
        log.info("Fixed outline from document: {} top-level sections", topLevel.size());
        if (log.isInfoEnabled()) {
            StringBuilder outlineDump = new StringBuilder();
            flattenForVerify(topLevel, outlineDump, 0);
            log.info("Recommended outline structure:\n{}", outlineDump.toString().trim());
        }

        // 트리 전체에서 leaf 노드와 full path 수집
        java.util.Map<String, OutlineNode> leafByPath = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> leafTitlePaths = new java.util.HashMap<>();
        collectLeaves(topLevel, "", leafByPath, "", leafTitlePaths);
        log.info("{} leaf nodes need LLM expansion for sub-sections", leafByPath.size());

        if (leafByPath.isEmpty()) {
            return topLevel;
        }

        if (progressCallback != null) {
            progressCallback.onProgress("권장 목차의 " + leafByPath.size() + "개 항목에 하위 구조를 구성합니다...");
        }

        String suggestions = requirementsSummary != null ? requirementsSummary : "";
        if (suggestions.length() > 12_000) {
            suggestions = suggestions.substring(0, 12_000) + TRUNCATION_SUFFIX;
        }
        final String reqSuggestions = suggestions;
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.GENERATION);

        // leaf 노드를 key 오름차순으로 정렬 (로마 숫자 포함 처리).
        List<java.util.Map.Entry<String, OutlineNode>> sortedLeaves =
                new ArrayList<>(leafByPath.entrySet());
        sortedLeaves.sort((a, b) -> compareKeyStrings(a.getKey(), b.getKey()));

        if (log.isInfoEnabled()) {
            String order = sortedLeaves.stream()
                    .map(e -> e.getKey() + " " + e.getValue().title())
                    .collect(java.util.stream.Collectors.joining(" | "));
            log.info("Leaf processing order ({}): {}", sortedLeaves.size(), order);
        }

        // 전역 사전 할당: 한 번의 LLM 호출로 모든 leaf에 주제·배점·의무항목을 배분.
        // 이렇게 하면 expandSection이 각 leaf의 구체적 주제를 이미 받은 상태에서 확장만 수행한다.
        List<LeafInfo> leafInfos = sortedLeaves.stream()
                .map(e -> new LeafInfo(e.getKey(), e.getValue().title(),
                        leafTitlePaths.getOrDefault(e.getKey(), "")))
                .toList();
        java.util.Map<String, ExpansionPlan> plans = planExpansion(leafInfos, reqSuggestions, rfpMandates, progressCallback);

        java.util.Map<String, List<OutlineNode>> expandedChildren = new java.util.LinkedHashMap<>();
        StringBuilder ledger = new StringBuilder();
        int total = sortedLeaves.size();
        int processed = 0;
        for (var entry : sortedLeaves) {
            processed++;
            String fullPath = entry.getKey();
            OutlineNode leaf = entry.getValue();
            String titlePath = leafTitlePaths.getOrDefault(fullPath, "");
            ExpansionPlan plan = plans.getOrDefault(fullPath, ExpansionPlan.empty());
            if (progressCallback != null) {
                progressCallback.onProgress("권장 목차 항목 " + processed + "/" + total + " 구성: " + leaf.title());
            }
            OutlineNode expanded = expandSection(client, leaf, reqSuggestions, fullPath, titlePath,
                    ledger.toString(), plan, rfpMandates);
            expandedChildren.put(fullPath, expanded.children());
            appendToLedger(ledger, fullPath, leaf.title(), expanded.children());
        }

        // 트리를 재구성: leaf 노드에 생성된 children 붙이기
        return rebuildWithChildren(topLevel, "", expandedChildren);
    }

    /**
     * leaf 정보. planExpansion LLM 입력에 사용된다.
     */
    private record LeafInfo(String key, String title, String titlePath) {}

    /**
     * 전역 사전 할당: 한 번의 LLM 호출로 모든 leaf에 주제·배점·의무항목을 분배한다.
     * 실패 또는 LLM 오류 시 빈 맵 반환 → expandSection이 기존 방식(ledger만)으로 fallback.
     */
    private java.util.Map<String, ExpansionPlan> planExpansion(List<LeafInfo> leaves,
                                                                 String requirementsSummary,
                                                                 RfpMandates rfpMandates,
                                                                 ProgressCallback progressCallback) {
        if (leaves == null || leaves.isEmpty()) {
            return java.util.Map.of();
        }
        // 계획 입력이 모두 비어있으면 (요구사항 없음, mandates 비어있음) 계획이 무의미
        boolean hasReqs = requirementsSummary != null && !requirementsSummary.isBlank()
                && !requirementsSummary.startsWith("없음");
        boolean hasMandates = rfpMandates != null && !rfpMandates.isEmpty();
        if (!hasReqs && !hasMandates) {
            log.info("Skipping expansion planning: no requirements and no mandates");
            return java.util.Map.of();
        }

        if (progressCallback != null) {
            progressCallback.onProgress(leaves.size() + "개 섹션에 주제·배점·의무항목을 전역 배분합니다...");
        }

        // 입력 JSON 빌드
        StringBuilder leavesBuf = new StringBuilder();
        for (LeafInfo leaf : leaves) {
            leavesBuf.append("- key=\"").append(leaf.key()).append("\"")
                    .append(", title=\"").append(leaf.title()).append("\"");
            if (leaf.titlePath() != null && !leaf.titlePath().isBlank()) {
                leavesBuf.append(", path=\"").append(leaf.titlePath()).append("\"");
            }
            leavesBuf.append("\n");
        }

        // 요구사항을 절단하지 않는 것이 중요. planExpansion은 단일 호출이므로 큰 프롬프트를 감당할 수 있음.
        // 절단되면 뒷부분 요구사항이 outline에 누락됨 (사용자 요구 위반).
        String reqText = hasReqs ? requirementsSummary : "없음";
        if (reqText.length() > 40_000) {
            log.warn("Requirements text truncated from {} to 40000 chars in planExpansion", reqText.length());
            reqText = reqText.substring(0, 40_000) + TRUNCATION_SUFFIX;
        }

        StringBuilder mandatesBuf = new StringBuilder();
        if (rfpMandates != null && rfpMandates.hasMandatoryItems()) {
            for (MandatoryItem item : rfpMandates.mandatoryItems()) {
                mandatesBuf.append("- ").append(item.id()).append(": ").append(item.title());
                if (item.description() != null && !item.description().isBlank()) {
                    mandatesBuf.append(" — ").append(item.description());
                }
                mandatesBuf.append("\n");
            }
        } else {
            mandatesBuf.append("없음");
        }

        StringBuilder weightsBuf = new StringBuilder();
        Integer totalScore = null;
        if (rfpMandates != null && rfpMandates.hasEvaluationWeights()) {
            for (var entry : rfpMandates.evaluationWeights().entrySet()) {
                weightsBuf.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("점\n");
            }
            totalScore = rfpMandates.totalScore();
        } else {
            weightsBuf.append("없음");
        }

        String prompt = promptLoader.load("generation-plan-expansion.txt");
        final String leavesParam = leavesBuf.toString();
        final String reqParam = reqText;
        final String mandatesParam = mandatesBuf.toString();
        final String weightsParam = weightsBuf.toString();
        final String totalScoreParam = totalScore != null ? totalScore.toString() : "미확보";

        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.GENERATION);
        String content;
        try {
            content = client.prompt()
                    .user(u -> u.text(prompt)
                            .param("leaves", leavesParam)
                            .param("requirements", reqParam)
                            .param("mandatoryItems", mandatesParam)
                            .param("evaluationWeights", weightsParam)
                            .param("totalScore", totalScoreParam))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("Expansion planning LLM call failed, falling back to empty plan: {}", e.getMessage());
            return java.util.Map.of();
        }

        java.util.Map<String, ExpansionPlan> plans = parseExpansionPlans(content, leaves);

        // Fix K: plan의 REQ-ID 중복 탐지 및 자동 제거 (원본 분리 회귀 대응)
        plans = dedupRequirementIdsInPlans(plans, leaves);

        // Fix D-3: 요구사항 커버리지 진단 — 입력 요구사항 중 몇 개가 plan topics에 반영됐는지 확인
        java.util.Set<String> allReqIds = extractRequirementIds(reqParam);
        detectMissingRequirements(plans, allReqIds);

        return plans;
    }

    /**
     * Fix K: plan 내의 REQ-ID 중복을 탐지하고 자동 제거한다.
     *
     * 알고리즘:
     * 1. 모든 plan topic에서 REQ-ID 추출
     * 2. 같은 REQ-ID가 두 leaf 이상에 등장하면 중복
     * 3. 중복 해소: LLM이 이미 관점 매칭을 시도했으므로, 다음 휴리스틱으로 canonical leaf 선택
     *    - 해당 REQ-ID prefix의 다른 REQ들을 가장 많이 가진 leaf를 canonical로 선택
     *      (이 leaf가 해당 prefix의 "홈" 역할을 하므로 의미적으로 가장 적합)
     *    - 동점이면 key 오름차순으로 먼저 오는 leaf 선택
     * 4. Non-canonical leaf의 topics에서 해당 REQ-ID를 제거
     *    - topic 내에 다른 REQ-ID가 있으면 ID만 제거 (topic 유지)
     *    - topic에 REQ-ID가 이것 하나뿐이면 topic 전체 제거
     */
    private java.util.Map<String, ExpansionPlan> dedupRequirementIdsInPlans(
            java.util.Map<String, ExpansionPlan> plans, List<LeafInfo> leaves) {
        if (plans == null || plans.isEmpty()) return plans;

        java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("([A-Z]{2,5}-\\d+)");

        // 1. REQ-ID → leaves 매핑 수집
        java.util.Map<String, List<String>> idToLeaves = new java.util.LinkedHashMap<>();
        // leaf → 해당 leaf가 가진 REQ-ID 목록 (prefix별 카운트용)
        java.util.Map<String, java.util.Map<String, Integer>> leafPrefixCount = new java.util.HashMap<>();

        for (var entry : plans.entrySet()) {
            String leafKey = entry.getKey();
            ExpansionPlan plan = entry.getValue();
            if (plan == null || !plan.hasTopics()) continue;
            java.util.Map<String, Integer> prefixCount = new java.util.HashMap<>();
            for (String topic : plan.topics()) {
                if (topic == null) continue;
                java.util.regex.Matcher m = idPattern.matcher(topic);
                while (m.find()) {
                    String id = m.group(1);
                    idToLeaves.computeIfAbsent(id, k -> new ArrayList<>()).add(leafKey);
                    String prefix = id.substring(0, id.indexOf('-'));
                    prefixCount.merge(prefix, 1, Integer::sum);
                }
            }
            leafPrefixCount.put(leafKey, prefixCount);
        }

        // 2. 중복 탐지
        java.util.Map<String, List<String>> duplicates = new java.util.LinkedHashMap<>();
        for (var entry : idToLeaves.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates.put(entry.getKey(), entry.getValue());
            }
        }

        if (duplicates.isEmpty()) {
            log.info("REQ-ID dedup: no duplicates found in plan");
            return plans;
        }

        log.warn("REQ-ID dedup: found {} REQ-IDs assigned to multiple leaves — resolving", duplicates.size());

        // 3. 각 중복 REQ-ID에 대해 canonical leaf 선택
        java.util.Map<String, String> canonicalChoice = new java.util.LinkedHashMap<>();
        for (var entry : duplicates.entrySet()) {
            String id = entry.getKey();
            List<String> leafKeys = entry.getValue();
            String prefix = id.substring(0, id.indexOf('-'));

            // 해당 prefix를 가장 많이 가진 leaf 선택 (tie 시 key 오름차순)
            String best = null;
            int bestCount = -1;
            for (String leafKey : new java.util.LinkedHashSet<>(leafKeys)) {
                int count = leafPrefixCount.getOrDefault(leafKey, java.util.Map.of()).getOrDefault(prefix, 0);
                if (count > bestCount
                        || (count == bestCount && (best == null || compareKeyStrings(leafKey, best) < 0))) {
                    best = leafKey;
                    bestCount = count;
                }
            }
            canonicalChoice.put(id, best);
            final String chosen = best;
            log.info("  dedup {}: canonical={}, removing from {}", id, chosen,
                    new java.util.LinkedHashSet<>(leafKeys).stream()
                            .filter(k -> !k.equals(chosen)).toList());
        }

        // 4. Non-canonical leaf의 topics에서 해당 REQ-ID 제거
        java.util.Map<String, ExpansionPlan> result = new java.util.LinkedHashMap<>();
        for (var entry : plans.entrySet()) {
            String leafKey = entry.getKey();
            ExpansionPlan plan = entry.getValue();
            if (plan == null || !plan.hasTopics()) {
                result.put(leafKey, plan != null ? plan : ExpansionPlan.empty());
                continue;
            }

            List<String> newTopics = new ArrayList<>();
            for (String topic : plan.topics()) {
                String filtered = removeNonCanonicalIds(topic, leafKey, canonicalChoice);
                if (filtered != null && !filtered.isBlank()) {
                    newTopics.add(filtered);
                }
            }
            result.put(leafKey, new ExpansionPlan(plan.weight(), newTopics, plan.mandatoryItemIds()));
        }

        return result;
    }

    /**
     * topic 문자열에서 non-canonical REQ-ID를 제거한다.
     * - topic에 ID가 canonical ID만 있으면 topic 유지 (null 아닌 값 반환)
     * - topic에 여러 ID가 있고 일부만 non-canonical이면 해당 ID만 빼고 유지
     * - topic에 있는 모든 ID가 non-canonical이면 null 반환 (topic 전체 제거)
     * - topic에 ID가 없으면 그대로 반환
     */
    private String removeNonCanonicalIds(String topic, String currentLeafKey,
                                           java.util.Map<String, String> canonicalChoice) {
        if (topic == null) return null;
        java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("([A-Z]{2,5}-\\d+)");
        java.util.regex.Matcher m = idPattern.matcher(topic);

        List<String> allIds = new ArrayList<>();
        List<String> keepIds = new ArrayList<>();
        while (m.find()) {
            String id = m.group(1);
            allIds.add(id);
            String canonical = canonicalChoice.get(id);
            if (canonical == null || canonical.equals(currentLeafKey)) {
                // 이 topic의 leaf가 canonical이거나, 해당 ID가 중복 해소 대상이 아님 → 유지
                keepIds.add(id);
            }
        }

        if (allIds.isEmpty()) {
            // ID 없는 topic (추상 topic) → 그대로
            return topic;
        }

        if (keepIds.isEmpty()) {
            // 모든 ID가 non-canonical → topic 전체 제거
            log.info("  removed topic '{}' from {} (all REQ-IDs moved)", topic, currentLeafKey);
            return null;
        }

        if (keepIds.size() == allIds.size()) {
            // 변경 없음
            return topic;
        }

        // 일부 ID만 제거: topic 문자열에서 non-canonical ID를 지움
        // 괄호 내 "A, B, C" 형태에서 특정 ID 제거
        String newTopic = topic;
        for (String id : allIds) {
            if (!keepIds.contains(id)) {
                // "A, B, C" → "A, C" (B 제거)
                newTopic = newTopic.replaceAll(",\\s*" + id, "");       // ", B"
                newTopic = newTopic.replaceAll(id + "\\s*,\\s*", "");   // "B, "
                newTopic = newTopic.replaceAll("\\(\\s*" + id + "\\s*\\)", ""); // "(B)" → ""
                newTopic = newTopic.replaceAll("\\b" + id + "\\b", "");  // standalone
            }
        }
        // 빈 괄호 "()" 제거 및 공백 정리
        newTopic = newTopic.replaceAll("\\(\\s*\\)", "").replaceAll("\\s+", " ").trim();
        log.info("  reduced topic from '{}' to '{}' in {}", topic, newTopic, currentLeafKey);
        return newTopic;
    }

    private java.util.Map<String, ExpansionPlan> parseExpansionPlans(String content, List<LeafInfo> leaves) {
        if (content == null || content.isBlank()) {
            log.warn("Expansion planning returned empty response");
            return java.util.Map.of();
        }
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            int objStart = json.indexOf('{');
            int objEnd = json.lastIndexOf('}');
            if (objStart < 0 || objEnd <= objStart) {
                log.warn("Expansion planning response is not JSON object: {}",
                        content.substring(0, Math.min(content.length(), 200)));
                return java.util.Map.of();
            }
            String jsonCandidate = json.substring(objStart, objEnd + 1);
            java.util.Map<String, ExpansionPlan> parsed = objectMapper.readValue(jsonCandidate,
                    new TypeReference<java.util.Map<String, ExpansionPlan>>() {});

            // 정규화: null 필드를 빈 리스트로, 필드 누락 leaf를 empty plan으로 채움
            java.util.Map<String, ExpansionPlan> normalized = new java.util.LinkedHashMap<>();
            for (LeafInfo leaf : leaves) {
                ExpansionPlan plan = parsed.get(leaf.key());
                if (plan == null) {
                    normalized.put(leaf.key(), ExpansionPlan.empty());
                } else {
                    normalized.put(leaf.key(), new ExpansionPlan(
                            plan.weight(),
                            plan.topics() != null ? plan.topics() : List.of(),
                            plan.mandatoryItemIds() != null ? plan.mandatoryItemIds() : List.of()));
                }
            }

            if (log.isInfoEnabled()) {
                int withWeight = (int) normalized.values().stream().filter(ExpansionPlan::hasWeight).count();
                int withTopics = (int) normalized.values().stream().filter(ExpansionPlan::hasTopics).count();
                int withMand = (int) normalized.values().stream().filter(ExpansionPlan::hasMandatoryItems).count();
                log.info("Expansion plan: {} leaves — weight:{}, topics:{}, mandatory:{}",
                        normalized.size(), withWeight, withTopics, withMand);
                normalized.forEach((key, plan) ->
                        log.info("  plan[{}]: weight={}, topics={}, mandIds={}",
                                key, plan.weight(), plan.topics(), plan.mandatoryItemIds()));
            }

            // Fix C: post-plan 중복 진단. 같은(또는 매우 유사한) topic이 두 leaf에 나타나면 경고 로그.
            detectDuplicateTopics(normalized);

            return normalized;
        } catch (Exception e) {
            log.warn("Failed to parse expansion plan: {} | preview: {}",
                    e.getMessage(), content.substring(0, Math.min(content.length(), 300)));
            return java.util.Map.of();
        }
    }

    /**
     * 요구사항 텍스트에서 REQ-ID(SFR-001, NFR-001 등)를 추출한다.
     * requirementsSummary 형식: "- [SFR-001] [상] item: description"
     */
    private static java.util.Set<String> extractRequirementIds(String requirementsSummary) {
        if (requirementsSummary == null || requirementsSummary.isBlank()) return java.util.Set.of();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[([A-Z]{2,5}-\\d+)\\]");
        java.util.regex.Matcher m = pattern.matcher(requirementsSummary);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /**
     * plan의 전체 topics에서 REQ-ID가 얼마나 등장하는지 검사.
     * 누락된 요구사항이 있으면 warning 로그.
     */
    private void detectMissingRequirements(java.util.Map<String, ExpansionPlan> plans,
                                             java.util.Set<String> allRequirementIds) {
        if (allRequirementIds.isEmpty()) return;

        // plan topics 전체에서 등장하는 REQ-ID 수집
        java.util.Set<String> foundIds = new java.util.HashSet<>();
        java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("([A-Z]{2,5}-\\d+)");
        for (ExpansionPlan plan : plans.values()) {
            if (plan == null || !plan.hasTopics()) continue;
            for (String topic : plan.topics()) {
                if (topic == null) continue;
                java.util.regex.Matcher m = idPattern.matcher(topic);
                while (m.find()) {
                    foundIds.add(m.group(1));
                }
            }
        }

        java.util.Set<String> missingIds = new java.util.LinkedHashSet<>(allRequirementIds);
        missingIds.removeAll(foundIds);

        int total = allRequirementIds.size();
        int covered = foundIds.size();
        int missing = missingIds.size();
        String pctStr = String.format("%.1f", total > 0 ? (covered * 100.0 / total) : 0.0);

        if (missing == 0) {
            log.info("Requirement coverage in plan: {}/{} (100.0%) — no missing requirements", covered, total);
        } else {
            String sample = String.join(", ", missingIds.stream().limit(20).toList()) + (missing > 20 ? " ..." : "");
            if (covered * 100.0 / total >= 80.0) {
                log.info("Requirement coverage in plan: {}/{} ({}%) — {} missing: {}",
                        covered, total, pctStr, missing, sample);
            } else {
                log.warn("Requirement coverage in plan: {}/{} ({}%) — {} requirements MISSING from plan topics: {}",
                        covered, total, pctStr, missing, sample);
                log.warn("Consider: (1) increase requirement prompt limit, (2) strengthen planExpansion prompt, (3) use more capable model");
            }
        }
    }

    /**
     * 트리를 순회하여 leaf 노드와 그 full path를 수집.
     * 자식 key가 이미 부모 key를 접두어로 포함하면 그대로 사용 (중복 방지).
     */
    private void collectLeaves(List<OutlineNode> nodes, String parentPath,
                                java.util.Map<String, OutlineNode> leafByPath,
                                String parentTitlePath,
                                java.util.Map<String, String> titlePaths) {
        for (OutlineNode node : nodes) {
            String fullPath = buildFullPath(parentPath, node.key());
            if (node.children().isEmpty()) {
                leafByPath.put(fullPath, node);
                if (titlePaths != null) {
                    titlePaths.put(fullPath, parentTitlePath);
                }
            } else {
                String titlePath = parentTitlePath.isEmpty() ? node.title() : parentTitlePath + " > " + node.title();
                collectLeaves(node.children(), fullPath, leafByPath, titlePath, titlePaths);
            }
        }
    }

    private List<OutlineNode> rebuildWithChildren(List<OutlineNode> nodes, String parentPath,
                                                   java.util.Map<String, List<OutlineNode>> expandedChildren) {
        List<OutlineNode> result = new ArrayList<>();
        for (OutlineNode node : nodes) {
            String fullPath = buildFullPath(parentPath, node.key());
            if (node.children().isEmpty()) {
                // leaf → 확장된 children 붙이기
                List<OutlineNode> newChildren = expandedChildren.getOrDefault(fullPath, List.of());
                result.add(new OutlineNode(node.key(), node.title(), node.description(), newChildren));
            } else {
                // 비-leaf → 재귀적으로 children 처리
                List<OutlineNode> rebuiltChildren = rebuildWithChildren(node.children(), fullPath, expandedChildren);
                result.add(new OutlineNode(node.key(), node.title(), node.description(), rebuiltChildren));
            }
        }
        return result;
    }

    /**
     * 부모 경로와 자식 key를 결합하여 full path 생성.
     * 자식 key가 이미 부모 경로를 포함하면 (예: 부모="I", 자식="I.1") 자식 key를 그대로 사용.
     */
    private String buildFullPath(String parentPath, String childKey) {
        if (parentPath.isEmpty()) return childKey;
        if (childKey.startsWith(parentPath + ".")) return childKey;
        return parentPath + "." + childKey;
    }


    /**
     * Map 단계: 요구사항 배치에 대한 목차 섹션 제안
     */
    private String mapOutlineSuggestion(String requirementsText, int batchNum, int totalBatches) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.GENERATION);

        String mapPrompt = """
                다음 요구사항 목록(배치 %d/%d)을 분석하고, 이 요구사항들을 반영하기 위해
                제안서 목차에 포함해야 할 섹션들을 제안하세요.

                ## 요구사항
                %s

                ## 출력 규칙
                - 각 요구사항이 어떤 섹션에 들어가야 하는지 구체적으로 제안
                - 섹션 제목은 구체적으로 (예: "에이전틱 AI 질의 의도 분석", "법령 메타데이터 자동분류")
                - "기능 1", "모듈 A" 같은 추상적 제목 금지
                - 관련 요구사항을 그룹핑하여 대분류 > 중분류 > 소분류 구조로 제안
                - 텍스트로 자유롭게 응답 (JSON 불필요)
                """.formatted(batchNum, totalBatches, requirementsText);

        return client.prompt()
                .user(mapPrompt)
                .call()
                .content();
    }

    /**
     * Reduce 단계: 2-pass로 목차 생성하여 출력 토큰 한도 회피.
     * Pass 1: 제안들을 합쳐서 대분류(1단계) 목록만 생성
     * Pass 2: 대분류별로 하위 구조(2~3단계)를 각각 생성
     */
    private List<OutlineNode> reduceOutline(List<String> customerChunks, String userInput,
                                             String rawSuggestions, RfpMandates rfpMandates) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.GENERATION);

        String rawContent = String.join(CHUNK_SEPARATOR, customerChunks);
        if (rawContent.length() > 20_000) {
            rawContent = rawContent.substring(0, 20_000) + TRUNCATION_SUFFIX;
        }
        final String suggestions = rawSuggestions.length() > 20_000
                ? rawSuggestions.substring(0, 20_000) + TRUNCATION_SUFFIX
                : rawSuggestions;

        // reduceOutline은 recommendedOutline이 없을 때만 호출됨
        String topicsSection = """
                ## 대분류 구성 가이드
                RFP의 주제와 요구사항 분석 결과를 바탕으로 적절한 대분류를 구성하세요.
                대분류는 일반적으로 다음과 같은 영역을 포함할 수 있습니다 (RFP 특성에 맞게 선택·조정):

                - 사업/서비스 개요 (배경, 목적, 범위)
                - 현황 분석
                - 전략/방향 (비전, 차별화, 기대효과)
                - 추진 체계·조직
                - 시스템·기능 구성 (요구사항 기반)
                - 적용 기술·아키텍처
                - 데이터·인터페이스
                - 보안·품질·성능
                - 수행 방안·일정
                - 프로젝트 관리·위험 관리
                - 투입 인력
                - 운영·유지보수·교육
                - 유사 실적
                - 기타사항

                ⚠️ 각 대분류에 대해 description에 그 섹션의 **관점**(사업·기술·운영·관리 등)을 명시하세요.
                관점이 다른 항목을 같은 섹션 하위에 섞지 마세요.
                """;

        // Pass 1: 대분류만 생성
        String pass1Prompt = """
                다음 고객 문서와 요구사항 분석 결과를 바탕으로, 제안서의 **대분류(1단계) 목차**만 생성하세요.

                ## 고객 문서 (요약)
                %s

                ## 추가 지시사항
                %s

                %s

                ## 요구사항 분석 결과 (목차 섹션 제안)
                %s

                ## 출력 규칙
                - 대분류 생성 (하위 항목은 생성하지 마세요)
                - 각 대분류에 description으로 어떤 내용/요구사항이 포함되어야 하는지 상세히 기술
                - children은 빈 배열 []로 두세요
                - 반드시 JSON 배열로만 응답 (다른 텍스트 없이)

                [{"key":"1","title":"대분류 제목","description":"포함할 내용 상세 설명","children":[]}]
                """.formatted(rawContent, userInput != null ? userInput : "", topicsSection, suggestions);

        String pass1Content = client.prompt().user(pass1Prompt).call().content();
        List<OutlineNode> topLevel = parseOutline(pass1Content);
        log.info("Outline reduce pass 1: {} top-level sections", topLevel.size());

        if (topLevel.isEmpty()) return topLevel;

        // Pass 2: 대분류를 key 오름차순으로 순차 확장.
        List<OutlineNode> sortedTopLevel = new ArrayList<>(topLevel);
        sortedTopLevel.sort(OutlineExtractor::compareKeys);

        // 전역 사전 할당: 한 번의 LLM 호출로 모든 대분류에 주제·배점·의무항목을 배분.
        List<LeafInfo> topInfos = sortedTopLevel.stream()
                .map(t -> new LeafInfo(t.key(), t.title(), ""))
                .toList();
        java.util.Map<String, ExpansionPlan> plans = planExpansion(topInfos, suggestions, rfpMandates, null);

        List<OutlineNode> finalOutline = new ArrayList<>();
        StringBuilder ledger = new StringBuilder();
        for (OutlineNode top : sortedTopLevel) {
            ExpansionPlan plan = plans.getOrDefault(top.key(), ExpansionPlan.empty());
            OutlineNode expanded = expandSection(client, top, suggestions, ledger.toString(), plan, rfpMandates);
            finalOutline.add(expanded);
            appendToLedger(ledger, top.key(), top.title(), expanded.children());
        }
        log.info("Outline reduce complete: {} top-level sections with children", finalOutline.size());
        return finalOutline;
    }

    /**
     * 대분류 하나를 받아서 중분류/소분류(2~3단계)를 생성.
     * keyPrefix가 없으면 topSection.key()를 사용.
     */
    private OutlineNode expandSection(ChatClient client, OutlineNode topSection, String suggestions,
                                       String topicLedger, ExpansionPlan plan, RfpMandates rfpMandates) {
        return expandSection(client, topSection, suggestions, topSection.key(), "", topicLedger, plan, rfpMandates);
    }

    private OutlineNode expandSection(ChatClient client, OutlineNode topSection, String suggestions,
                                       String keyPrefix, String titlePath, String topicLedger,
                                       ExpansionPlan plan, RfpMandates rfpMandates) {
        // Strict 모드: plan에 topics가 있으면 코드 차원으로 children titles를 강제
        // LLM은 description + (필요 시) grandchildren만 채움
        if (plan != null && plan.hasTopics()) {
            return expandWithStrictPlan(client, topSection, keyPrefix, titlePath, plan, rfpMandates);
        }
        String parentContext = titlePath.isEmpty() ? "" : """

                ## ⚠️ 상위 맥락 (매우 중요!)
                이 항목은 **"%s > %s"** 아래에 있습니다.
                하위 목차는 반드시 이 상위 맥락의 주제 범위 안에서만 구성하세요.
                상위 항목과 무관한 내용(사업 도메인의 일반 현황 등)을 하위에 넣지 마세요.
                """.formatted(titlePath, topSection.title());
        String ledgerContext = (topicLedger == null || topicLedger.isBlank()) ? "" : """

                ## ⚠️ 이미 다른 섹션에 배치된 주제 (절대 중복 금지!)
                아래 주제들은 이미 다른 섹션이 다루기로 결정되었습니다.
                - **같은 주제는 절대 다시 만들지 마세요.**
                - 의미상 동일한 주제(예: "성능 관리" ↔ "응답속도 최적화", "위험관리" ↔ "리스크 대응", "보안" ↔ "암호화·접근제어")도 중복으로 간주합니다.
                - 이 항목에서 자연스럽게 다룰 수 있는 주제가 이미 다른 섹션에 있다면, **이 항목 고유의 관점**으로만 children을 만드세요.
                - 고유한 children이 없다면 빈 배열 []을 반환해도 됩니다.

                %s
                """.formatted(topicLedger);
        String weightContext = buildWeightContext(plan, rfpMandates);
        String topicsContext = buildTopicsContext(plan);
        String mandatoryContext = buildMandatoryContext(plan, rfpMandates);
        String expandPrompt = """
                다음 제안서 항목의 하위 목차(중분류, 소분류)를 구성하세요.
                이 제안서는 **프레젠테이션형 문서**이므로 하나의 리프 항목 = 하나의 장표(슬라이드)입니다.

                ## 상위 항목
                - key: %s
                - title: %s
                - description: %s
                %s%s%s%s%s
                ## ⚠️ 핵심 원칙: 섹션 성격에 맞는 관점으로 하위 항목을 구성하세요

                상위 항목의 title과 description을 보고 이 섹션의 **관점**을 먼저 판단하세요.
                일반적으로 다음 두 부류 중 하나입니다:

                - **사업/관리 관점 섹션** (전략·체계·방법론·관리·운영·인력 등)
                  → 사업·프로젝트 관점의 하위 항목으로 구성
                  → 기술 구현 세부사항(아키텍처·인프라·모델 등)을 하위 항목으로 넣지 마세요
                  → 요구사항 ID를 description에 넣지 마세요

                - **기술/구현 관점 섹션** (시스템·기능·아키텍처·구현·데이터 등)
                  → 기술·구현 관점의 하위 항목으로 구성
                  → 요구사항 item 이름을 그대로 사용하고 description에 RFP 원본 ID 포함

                관점이 섞이면 안 됩니다. "전략"이라는 제목 아래에 기술 구현이 들어가거나, "기술"이라는 제목 아래에 사업 전략이 들어가는 것은 잘못입니다.

                ## 참고할 요구사항 분석 결과
                아래는 전체 요구사항 목록입니다. **기술/구현 관점 섹션일 때만** 관련 요구사항을 참고하세요.
                사업/관리 관점 섹션이면 요구사항 목록을 무시하고, 사업·프로젝트 관점으로만 하위 항목을 구성하세요.
                %s

                ## 출력 규칙
                - 이 항목의 하위 구조를 JSON으로 생성
                - **"이 섹션에서 다뤄야 할 주제" 블록이 있으면**: 각 주제를 하나씩 child로 만드세요 (주제 개수 = children 개수)
                - 주제 블록이 없으면: 배점 정보 가이드를 따르거나, 2~5개로 균등 분배
                - key는 "%s.1", "%s.1.1" 형식
                - **구체적이고 고유한 제목 사용 필수** ("기능 1" 같은 placeholder, "주요 기능" 같은 추상 제목 금지)
                - 반드시 JSON 배열로만 응답 (children 포함한 배열)

                [{"key":"%s.1","title":"하위 제목","description":"설명","children":[]}]
                """.formatted(keyPrefix, topSection.title(), topSection.description(),
                parentContext, ledgerContext, weightContext, topicsContext, mandatoryContext,
                suggestions.length() > 8_000 ? suggestions.substring(0, 8_000) : suggestions,
                keyPrefix, keyPrefix, keyPrefix);

        String content = client.prompt().user(expandPrompt).call().content();
        List<OutlineNode> children = parseOutline(content);
        if (log.isInfoEnabled()) {
            log.info("Expanded section '{}' (path={}): {} children", topSection.title(), keyPrefix, children.size());
        }

        return new OutlineNode(topSection.key(), topSection.title(), topSection.description(), children);
    }

    /**
     * 사전 할당된 ExpansionPlan의 weight를 사용하여 배점 안내 블록을 만든다.
     * plan이 weight를 갖지 않거나 totalScore가 없으면 빈 문자열 반환.
     */
    private String buildWeightContext(ExpansionPlan plan, RfpMandates rfpMandates) {
        if (plan == null || !plan.hasWeight()) return "";
        Integer sectionWeight = plan.weight();
        Integer totalScore = rfpMandates != null ? rfpMandates.totalScore() : null;
        if (sectionWeight == null || sectionWeight <= 0 || totalScore == null || totalScore <= 0) return "";

        double pct = (sectionWeight * 100.0) / totalScore;
        String tier;
        String guide;
        if (pct >= 15.0) {
            tier = "높은 배점";
            guide = "children 5~7개, 필요 시 3단계까지 전개";
        } else if (pct >= 8.0) {
            tier = "중간 배점";
            guide = "children 4~5개";
        } else {
            tier = "낮은 배점";
            guide = "children 2~3개, 2단계로 종료";
        }
        return """

                ## 배점 정보
                이 섹션의 평가 배점: %d점 / 총 %d점 (%.1f%% — %s)
                → 권장 children 구성: %s
                """.formatted(sectionWeight, totalScore, pct, tier, guide);
    }

    /**
     * 사전 할당된 주제 목록 블록을 만든다. LLM에게 이 주제들을 children으로 만들도록 지시.
     * 주제가 없으면 빈 문자열 반환 → LLM이 자유 구성.
     */
    private String buildTopicsContext(ExpansionPlan plan) {
        if (plan == null || !plan.hasTopics()) return "";
        StringBuilder sb = new StringBuilder();
        for (String topic : plan.topics()) {
            sb.append("- ").append(topic).append("\n");
        }
        return """

                ## 🎯 이 섹션에서 다뤄야 할 주제 (전역 사전 배분 결과)
                아래 주제들은 이 outline의 모든 섹션을 한 번에 보고, 중복 없이 전역적으로 배분된 것입니다.
                **각 주제를 하나씩 child로 만드세요. 주제 개수 = children 개수.**
                - 임의로 주제를 추가하거나 빼지 마세요 (다른 섹션과 중복을 일으킵니다).
                - 각 child의 title은 주제를 구체적으로 풀어서 작성하고, description에 상세 내용을 기술.
                - 배점이 높은 섹션이면 각 child 아래에 2~3개의 소분류(grandchild)를 추가해도 됩니다.

                %s
                """.formatted(sb);
    }

    /**
     * 사전 할당된 의무 작성 항목 블록을 만든다.
     * plan의 mandatoryItemIds로 필터링하여 이 섹션에 배치된 항목만 포함.
     */
    private String buildMandatoryContext(ExpansionPlan plan, RfpMandates rfpMandates) {
        if (plan == null || !plan.hasMandatoryItems() || rfpMandates == null || !rfpMandates.hasMandatoryItems()) return "";
        java.util.Map<String, MandatoryItem> byId = new java.util.HashMap<>();
        for (MandatoryItem item : rfpMandates.mandatoryItems()) {
            byId.put(item.id(), item);
        }
        StringBuilder sb = new StringBuilder();
        for (String id : plan.mandatoryItemIds()) {
            MandatoryItem item = byId.get(id);
            if (item == null) continue;
            sb.append("- [").append(item.id()).append("] ").append(item.title());
            if (item.description() != null && !item.description().isBlank()) {
                sb.append(": ").append(item.description());
            }
            if (item.sourceHint() != null && !item.sourceHint().isBlank()) {
                sb.append(" (").append(item.sourceHint()).append(")");
            }
            sb.append("\n");
        }
        if (sb.isEmpty()) return "";
        return """

                ## 📌 이 섹션에 배치된 의무 작성 항목 (RFP 명시)
                아래 의무 항목들은 전역 배분 결과 이 섹션에 배치되었습니다.
                **반드시 이 섹션의 children에 포함시키세요.** (별도 child로 만들거나 관련 child에 녹여넣기)

                %s
                """.formatted(sb);
    }

    /**
     * 전역 plan에서 topic 중복 탐지. 정규화된 topic이 두 leaf에 나타나면 warning 로그.
     * 진단용 — 자동 수정은 하지 않음.
     */
    private void detectDuplicateTopics(java.util.Map<String, ExpansionPlan> plans) {
        java.util.Map<String, List<String>> topicToLeaves = new java.util.HashMap<>();
        for (var entry : plans.entrySet()) {
            String leafKey = entry.getKey();
            ExpansionPlan plan = entry.getValue();
            if (plan == null || !plan.hasTopics()) continue;
            for (String topic : plan.topics()) {
                String normalized = topic == null ? "" : topic.replaceAll("\\s+", "").toLowerCase();
                if (normalized.isEmpty()) continue;
                topicToLeaves.computeIfAbsent(normalized, k -> new ArrayList<>()).add(leafKey);
            }
        }
        int duplicates = 0;
        for (var entry : topicToLeaves.entrySet()) {
            List<String> leaves = entry.getValue();
            if (leaves.size() > 1) {
                duplicates++;
                log.warn("Plan duplicate topic: '{}' assigned to {} leaves: {}",
                        entry.getKey(), leaves.size(), leaves);
            }
        }
        if (duplicates > 0) {
            log.warn("Plan duplicate detection: {} topics assigned to multiple leaves. " +
                    "Consider strengthening the planExpansion prompt or switching to a more capable model.",
                    duplicates);
        } else {
            log.info("Plan duplicate detection: no exact-match duplicates found");
        }
    }

    /**
     * Strict 모드 확장: plan.topics를 children titles로 강제하고,
     * LLM은 description과 (필요 시) grandchildren만 채우도록 한다.
     *
     * 동작:
     * 1. plan.topics를 순서대로 skeleton children으로 변환 (key/title 사전 할당)
     * 2. 배점이 높으면 grandchildren 생성을 LLM에 요청
     * 3. LLM 응답을 파싱하되, key/title은 skeleton 값으로 강제 덮어쓰기
     * 4. LLM이 deviate해도 plan.topics가 결과에 정확히 반영됨
     */
    private OutlineNode expandWithStrictPlan(ChatClient client, OutlineNode topSection,
                                              String keyPrefix, String titlePath,
                                              ExpansionPlan plan, RfpMandates rfpMandates) {
        // Skeleton 구축: topics에서 REQ-ID를 추출하여 clean title + req ID list 분리
        List<String> topics = plan.topics();
        List<OutlineNode> skeleton = new ArrayList<>();
        // skeleton[i]에 해당하는 REQ-ID 목록 (description 프리픽스에 사용)
        List<List<String>> skeletonReqIds = new ArrayList<>();
        for (int i = 0; i < topics.size(); i++) {
            String childKey = keyPrefix + "." + (i + 1);
            String topic = topics.get(i);
            TopicParse parsed = parseTopicForIds(topic);
            skeleton.add(new OutlineNode(childKey, parsed.cleanTitle, "", List.of()));
            skeletonReqIds.add(parsed.reqIds);
        }

        // 배점에 따라 grandchildren 필요 여부 결정
        boolean needsGrandchildren = false;
        Integer totalScore = rfpMandates != null ? rfpMandates.totalScore() : null;
        if (plan.hasWeight() && totalScore != null && totalScore > 0) {
            double pct = (plan.weight() * 100.0) / totalScore;
            needsGrandchildren = pct >= 10.0;
        }

        // 의무 항목 텍스트
        String mandatoryText = "";
        if (plan.hasMandatoryItems() && rfpMandates != null && rfpMandates.hasMandatoryItems()) {
            java.util.Map<String, MandatoryItem> byId = new java.util.HashMap<>();
            for (MandatoryItem item : rfpMandates.mandatoryItems()) {
                byId.put(item.id(), item);
            }
            StringBuilder mb = new StringBuilder("\n## 이 섹션에 배치된 의무 작성 항목 (반드시 description에 반영)\n");
            for (String id : plan.mandatoryItemIds()) {
                MandatoryItem item = byId.get(id);
                if (item == null) continue;
                mb.append("- [").append(id).append("] ").append(item.title());
                if (item.description() != null && !item.description().isBlank()) {
                    mb.append(": ").append(item.description());
                }
                mb.append("\n");
            }
            mandatoryText = mb.toString();
        }

        StringBuilder skeletonText = new StringBuilder();
        for (OutlineNode child : skeleton) {
            skeletonText.append("- key=\"").append(child.key())
                    .append("\", title=\"").append(child.title()).append("\"\n");
        }

        String parentLine = titlePath.isEmpty() ? topSection.title() : titlePath + " > " + topSection.title();
        String grandPart = needsGrandchildren
                ? "- 각 child에 **2~3개의 grandchild를 추가하세요** (소분류, 제목 구체적으로)"
                : "- grandchild는 추가하지 마세요 (children은 leaf로 유지)";

        String prompt = """
                다음 섹션의 children 구성이 이미 확정되어 있습니다.
                **각 child의 key와 title은 절대 변경하지 마세요.** description만 채우고, 필요 시 grandchild를 추가하세요.

                ## 상위 섹션
                - key: %s
                - title: %s
                - 상위 경로: %s

                ## 확정된 children (key/title 변경 금지!)
                %s
                %s
                ## 작업
                각 child에 대해:
                - description: 1~3문장으로 해당 주제가 어떤 내용을 다룰지 설명
                %s
                - title은 위 목록 그대로 사용 (절대 변경 금지)
                - key는 위 목록 그대로 사용 (절대 변경 금지)
                - children 목록 순서도 위 목록 그대로 유지

                ## 출력 형식 (JSON 배열만, 다른 텍스트 없이)
                [
                  {"key":"<위 그대로>","title":"<위 그대로>","description":"...","children":[]}
                ]
                """.formatted(keyPrefix, topSection.title(), parentLine, skeletonText, mandatoryText, grandPart);

        String content;
        try {
            content = client.prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.warn("Strict expansion LLM call failed for {}, returning skeleton: {}", keyPrefix, e.getMessage());
            return new OutlineNode(topSection.key(), topSection.title(), topSection.description(), skeleton);
        }

        List<OutlineNode> llmChildren = parseOutline(content);

        // 병합: skeleton의 key/title을 강제로 사용, LLM의 description과 children만 채택
        // Fix L: REQ-ID가 원본 topic에 있었다면 description 앞에 "[관련 요구사항: ID, ID] " 프리픽스 추가
        List<OutlineNode> merged = new ArrayList<>();
        for (int i = 0; i < skeleton.size(); i++) {
            OutlineNode sk = skeleton.get(i);
            List<String> reqIds = skeletonReqIds.get(i);
            // LLM 응답에서 key로 매칭 시도
            OutlineNode llmMatch = null;
            for (OutlineNode candidate : llmChildren) {
                if (sk.key().equals(candidate.key())) {
                    llmMatch = candidate;
                    break;
                }
            }
            // 매칭 실패 시 순서로 fallback
            if (llmMatch == null && i < llmChildren.size()) {
                llmMatch = llmChildren.get(i);
            }

            String llmDescription = (llmMatch != null && llmMatch.description() != null)
                    ? llmMatch.description() : "";
            List<OutlineNode> grandchildren = (llmMatch != null && llmMatch.children() != null)
                    ? llmMatch.children() : List.of();

            // REQ-ID가 있으면 description 앞에 프리픽스 추가 (목차 가독성 향상)
            String description = llmDescription;
            if (!reqIds.isEmpty()) {
                String reqPrefix = "[관련 요구사항: " + String.join(", ", reqIds) + "] ";
                description = reqPrefix + llmDescription;
            }

            merged.add(new OutlineNode(sk.key(), sk.title(), description, grandchildren));
        }

        if (log.isInfoEnabled()) {
            log.info("Strict expansion '{}' (path={}): {} children from plan, grandchildren={}",
                    topSection.title(), keyPrefix, merged.size(), needsGrandchildren);
        }

        return new OutlineNode(topSection.key(), topSection.title(), topSection.description(), merged);
    }

    /**
     * topic 문자열에서 REQ-ID를 추출하여 clean title과 ID 목록으로 분리한다.
     *
     * 입력 예시:
     *   "법령특화 생성형 AI 모델 개발 (SFR-001, REQ-13)"
     *   → cleanTitle: "법령특화 생성형 AI 모델 개발"
     *   → reqIds: [SFR-001, REQ-13]
     *
     *   "사업 비전 및 목표"  (ID 없음)
     *   → cleanTitle: "사업 비전 및 목표"
     *   → reqIds: []
     */
    private record TopicParse(String cleanTitle, List<String> reqIds) {}

    private TopicParse parseTopicForIds(String topic) {
        if (topic == null || topic.isBlank()) {
            return new TopicParse(topic != null ? topic : "", List.of());
        }
        java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("([A-Z]{2,5}-\\d+)");
        java.util.regex.Matcher m = idPattern.matcher(topic);
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            String id = m.group(1);
            if (!ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) {
            return new TopicParse(topic.trim(), List.of());
        }
        // 괄호 안에 ID들이 있는 패턴 제거: "(SFR-001, REQ-13)" → 제거
        String cleanTitle = topic.replaceAll("\\s*\\([^()]*[A-Z]{2,5}-\\d+[^()]*\\)", "");
        // 괄호 밖에 남은 standalone ID도 제거
        cleanTitle = cleanTitle.replaceAll("\\s*[A-Z]{2,5}-\\d+\\s*", " ");
        cleanTitle = cleanTitle.replaceAll("\\s+", " ").trim();
        // 빈 title 방지
        if (cleanTitle.isEmpty()) cleanTitle = topic.trim();
        return new TopicParse(cleanTitle, ids);
    }

    /**
     * 글로벌 토픽 원장에 한 leaf의 확장 결과를 추가한다.
     * 후속 expandSection 호출이 이 원장을 보고 중복 주제를 회피한다.
     */
    private void appendToLedger(StringBuilder ledger, String fullPath, String parentTitle, List<OutlineNode> children) {
        if (children.isEmpty()) return;
        ledger.append("[").append(fullPath).append(" ").append(parentTitle).append("]\n");
        for (OutlineNode child : children) {
            ledger.append("  - ").append(child.key()).append(" ").append(child.title()).append("\n");
            for (OutlineNode grand : child.children()) {
                ledger.append("    - ").append(grand.key()).append(" ").append(grand.title()).append("\n");
            }
        }
    }

    /**
     * 요구사항이 적을 때: 단일 호출로 목차 추출
     */
    private List<OutlineNode> extractDirect(List<String> customerChunks, String userInput, String reqs) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.GENERATION);
        String prompt = promptLoader.load("generation-extract-outline.txt");

        String rawContent = String.join(CHUNK_SEPARATOR, customerChunks);
        int contentLimit = reqs.length() > 500 ? 25_000 : 50_000;
        if (rawContent.length() > contentLimit) {
            rawContent = rawContent.substring(0, contentLimit) + TRUNCATION_SUFFIX;
        }
        final String documentContent = rawContent;
        String input = userInput != null ? userInput : "";

        log.info("Outline direct prompt sizes: document={}chars, requirements={}chars",
                documentContent.length(), reqs.length());

        String content = client.prompt()
                .user(u -> u.text(prompt)
                        .param("documentContent", documentContent)
                        .param("userInput", input)
                        .param("requirements", reqs))
                .call()
                .content();

        log.info("Outline LLM response: length={}, preview={}",
                content != null ? content.length() : 0,
                content != null ? content.substring(0, Math.min(content.length(), 500)) : "null");
        List<OutlineNode> outline = parseOutline(content);
        log.info("Extracted outline: {} top-level sections", outline.size());
        return outline;
    }

    private List<List<Requirement>> splitRequirements(List<Requirement> requirements, int charLimit) {
        List<List<Requirement>> batches = new ArrayList<>();
        List<Requirement> currentBatch = new ArrayList<>();
        int currentSize = 0;

        for (Requirement r : requirements) {
            int lineLen = r.id().length() + r.item().length() + r.description().length() + 20;
            if (currentSize + lineLen > charLimit && !currentBatch.isEmpty()) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentSize = 0;
            }
            currentBatch.add(r);
            currentSize += lineLen;
        }
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }
        return batches;
    }

    public String toJson(List<OutlineNode> outline) {
        try {
            return objectMapper.writeValueAsString(outline);
        } catch (Exception e) {
            throw new RagException("Failed to serialize outline", e);
        }
    }

    public List<OutlineNode> fromJson(String json) {
        try {
            return objectMapper.readValue(json, OUTLINE_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse outline JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 생성된 outline에서 의무 작성 항목 커버리지를 검증한다.
     * 각 의무 항목이 어떤 leaf에서 다뤄지는지 LLM에게 매핑을 요청하고,
     * 어디에도 매핑되지 않은 항목 목록을 반환한다.
     *
     * @return 누락된 의무 항목 목록 (전부 커버되었으면 빈 리스트)
     */
    public List<MandatoryItem> verifyMandatoryItemCoverage(List<OutlineNode> outline, List<MandatoryItem> items) {
        if (items == null || items.isEmpty() || outline == null || outline.isEmpty()) {
            return List.of();
        }

        StringBuilder outlineSb = new StringBuilder();
        flattenForVerify(outline, outlineSb, 0);

        StringBuilder itemSb = new StringBuilder();
        for (MandatoryItem item : items) {
            itemSb.append("- ").append(item.id()).append(": ").append(item.title());
            if (item.description() != null && !item.description().isBlank()) {
                itemSb.append(" — ").append(item.description());
            }
            itemSb.append("\n");
        }

        String prompt = """
                다음은 제안서 outline과 RFP가 명시한 의무 작성 항목 목록입니다.
                각 의무 항목이 outline의 어느 leaf에서 다뤄지고 있는지 매핑하세요.
                다뤄지고 있지 않으면 해당 항목 ID에 "NONE"을 답하세요.

                ## Outline
                %s

                ## 의무 작성 항목
                %s

                ## 출력 형식 (JSON 객체만, 다른 텍스트 없이)
                {"MAND-01": "II.1.2", "MAND-02": "NONE", ...}
                """.formatted(outlineSb.toString().trim(), itemSb);

        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.GENERATION);
        String content;
        try {
            content = client.prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.warn("Mandatory coverage verification LLM call failed: {}", e.getMessage());
            return List.of();
        }

        java.util.Map<String, String> coverage = parseCoverageMap(content);
        List<MandatoryItem> uncovered = items.stream()
                .filter(i -> {
                    String mapped = coverage.get(i.id());
                    return mapped == null || mapped.isBlank() || "NONE".equalsIgnoreCase(mapped.trim());
                })
                .toList();

        log.info("Mandatory item coverage: {}/{} covered, {} uncovered",
                items.size() - uncovered.size(), items.size(), uncovered.size());
        return uncovered;
    }

    private void flattenForVerify(List<OutlineNode> nodes, StringBuilder sb, int depth) {
        for (OutlineNode node : nodes) {
            sb.append("  ".repeat(depth)).append(node.key()).append(". ").append(node.title());
            if (node.description() != null && !node.description().isBlank()) {
                sb.append(" — ").append(node.description());
            }
            sb.append("\n");
            if (!node.children().isEmpty()) {
                flattenForVerify(node.children(), sb, depth + 1);
            }
        }
    }

    private java.util.Map<String, String> parseCoverageMap(String content) {
        if (content == null || content.isBlank()) return java.util.Map.of();
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            int objStart = json.indexOf('{');
            int objEnd = json.lastIndexOf('}');
            if (objStart < 0 || objEnd <= objStart) return java.util.Map.of();
            String jsonCandidate = json.substring(objStart, objEnd + 1);
            return objectMapper.readValue(jsonCandidate,
                    new TypeReference<java.util.Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse mandatory coverage map: {}", e.getMessage());
            return java.util.Map.of();
        }
    }

    private List<OutlineNode> parseOutline(String content) {
        if (content == null || content.isBlank()) {
            log.warn("Outline LLM response is null or blank");
            return List.of();
        }
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            // JSON 배열 시작점 탐색 (LLM이 앞에 설명 텍스트를 붙이는 경우 대비)
            int arrStart = json.indexOf('[');
            int arrEnd = json.lastIndexOf(']');
            if (arrStart >= 0 && arrEnd > arrStart) {
                json = json.substring(arrStart, arrEnd + 1);
            }
            return objectMapper.readValue(json, OUTLINE_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse outline JSON: {} | response preview: {}",
                    e.getMessage(), content.substring(0, Math.min(content.length(), 500)));
            return List.of();
        }
    }

    /**
     * "1", "1.1", "2", "10", "10.1" 같은 계층 번호를 자연수 순서로 비교.
     */
    private static int compareKeys(OutlineNode a, OutlineNode b) {
        return compareKeyStrings(a.key(), b.key());
    }

    static int compareKeyStrings(String a, String b) {
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
        String trimmed = s == null ? "" : s.trim();
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            // Roman numeral fallback (I, II, III, IV, V, VI, VII, VIII, IX, X, XI, ...)
            int roman = romanToInt(trimmed);
            return roman > 0 ? roman : Integer.MAX_VALUE;
        }
    }

    /**
     * 로마 숫자를 정수로 변환. 유효한 로마 숫자가 아니면 0을 반환.
     * "II" → 2, "III" → 3, "IV" → 4, "VIII" → 8, "IX" → 9 ...
     */
    private static int romanToInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        String upper = s.toUpperCase();
        int total = 0;
        int prev = 0;
        for (int i = upper.length() - 1; i >= 0; i--) {
            int val = switch (upper.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                case 'M' -> 1000;
                default -> 0;
            };
            if (val == 0) return 0; // invalid roman
            if (val < prev) total -= val;
            else total += val;
            prev = val;
        }
        return total;
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String message);
    }
}
