package com.example.rag.questionnaire.workflow;

import com.example.rag.common.PromptLoader;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RequirementExtractor {

    private static final Logger log = LoggerFactory.getLogger(RequirementExtractor.class);
    private static final int BATCH_CHAR_LIMIT = 50_000;
    private static final int MAX_PARALLEL = 3;
    private static final String CHUNK_SEPARATOR = "\n---\n";
    private static final TypeReference<List<Requirement>> REQ_LIST_TYPE = new TypeReference<>() {};

    private static final Comparator<Requirement> IMPORTANCE_ORDER = Comparator.comparingInt(r ->
            switch (r.importance()) {
                case "상" -> 0;
                case "중" -> 1;
                default -> 2;
            });

    private final ModelClientProvider modelClientProvider;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public RequirementExtractor(ModelClientProvider modelClientProvider,
                                 PromptLoader promptLoader,
                                 ObjectMapper objectMapper) {
        this.modelClientProvider = modelClientProvider;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
    }

    public List<Requirement> extract(List<String> customerChunks, String userInput,
                                      QuestionnaireGeneratorService.ProgressCallback callback) {
        return extract(customerChunks, userInput, callback, null);
    }

    public List<Requirement> extract(List<String> customerChunks, String userInput,
                                      QuestionnaireGeneratorService.ProgressCallback callback,
                                      java.util.function.Consumer<List<Requirement>> partialResultCallback) {
        String documentContent = String.join(CHUNK_SEPARATOR, customerChunks);
        String input = userInput != null ? userInput : "";

        List<Requirement> requirements;
        if (documentContent.length() <= BATCH_CHAR_LIMIT) {
            requirements = extractFromContent(documentContent, input);
            if (partialResultCallback != null) {
                partialResultCallback.accept(deduplicateAndReindex(requirements));
            }
        } else {
            requirements = extractWithMapReduce(customerChunks, input, callback, partialResultCallback);
        }

        // 중요도 순 정렬
        requirements = new ArrayList<>(requirements);
        requirements.sort(IMPORTANCE_ORDER);

        log.info("Extracted {} requirements (상:{}, 중:{}, 하:{})",
                requirements.size(),
                requirements.stream().filter(r -> "상".equals(r.importance())).count(),
                requirements.stream().filter(r -> "중".equals(r.importance())).count(),
                requirements.stream().filter(r -> "하".equals(r.importance())).count());

        return requirements;
    }

    private List<Requirement> extractFromContent(String documentContent, String userInput) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);
        String prompt = promptLoader.load("questionnaire-extract-requirements.txt");

        String content = client.prompt()
                .user(u -> u.text(prompt)
                        .param("documentContent", documentContent)
                        .param("userInput", userInput))
                .call()
                .content();

        return parseRequirements(content);
    }

    private List<Requirement> extractWithMapReduce(List<String> chunks, String userInput,
                                                    QuestionnaireGeneratorService.ProgressCallback callback,
                                                    java.util.function.Consumer<List<Requirement>> partialResultCallback) {
        List<List<String>> batches = splitIntoBatches(chunks);

        if (batches.size() <= 1) {
            // 배치가 1개면 병렬화 불필요
            String batchContent = String.join(CHUNK_SEPARATOR, batches.get(0));
            callback.onProgress("요구사항 추출 중... (1/1 배치)");
            List<Requirement> result = extractFromContent(batchContent, userInput);
            if (partialResultCallback != null) {
                partialResultCallback.accept(deduplicateAndReindex(result));
            }
            return deduplicateAndReindex(result);
        }

        // 병렬 Map: 최대 MAX_PARALLEL개 동시 실행
        callback.onProgress("요구사항 추출 중... (총 " + batches.size() + "개 배치, 최대 " + MAX_PARALLEL + "개 병렬 처리)");
        List<Requirement> allRequirements = Collections.synchronizedList(new ArrayList<>());
        Semaphore semaphore = new Semaphore(MAX_PARALLEL);
        AtomicInteger completedCount = new AtomicInteger(0);
        int totalBatches = batches.size();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < totalBatches; i++) {
            String batchContent = String.join(CHUNK_SEPARATOR, batches.get(i));
            int batchNum = i + 1;

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        List<Requirement> partial = extractFromContent(batchContent, userInput);
                        allRequirements.addAll(partial);
                        int done = completedCount.incrementAndGet();
                        log.info("Batch {}/{}: extracted {} requirements", batchNum, totalBatches, partial.size());
                        callback.onProgress("요구사항 추출 중... (" + done + "/" + totalBatches + " 배치 완료)");

                        if (partialResultCallback != null) {
                            synchronized (allRequirements) {
                                partialResultCallback.accept(deduplicateAndReindex(new ArrayList<>(allRequirements)));
                            }
                        }
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Batch extraction interrupted", e);
                }
            });
            futures.add(future);
        }

        // 모든 배치 완료 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Reduce: 최종 중복 제거 및 ID 재부여
        callback.onProgress("요구사항을 정리하고 있습니다...");
        return deduplicateAndReindex(new ArrayList<>(allRequirements));
    }

    private List<Requirement> deduplicateAndReindex(List<Requirement> requirements) {
        // 항목명 기준으로 중복 제거 (첫 번째 것 유지)
        List<Requirement> deduplicated = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Requirement req : requirements) {
            String key = req.category() + "|" + req.item();
            if (seen.add(key)) {
                deduplicated.add(req);
            }
        }

        // 원본 ID 보존: 문서에 명시된 ID(SFR-001, NFR-001 등)는 유지하고,
        // ID가 없거나 일반적인 경우에만 자동 부여
        java.util.Set<String> usedIds = new java.util.HashSet<>();
        List<Requirement> result = new ArrayList<>();
        int autoIndex = 1;

        for (Requirement r : deduplicated) {
            String id = r.id();
            if (id != null && !id.isBlank() && !id.matches("(?i)^REQ-\\d+$") && usedIds.add(id)) {
                // 문서 원본 ID 보존 (SFR-001, NFR-001, IFR-001 등)
                result.add(r);
            } else {
                // ID가 없거나 중복이면 자동 부여
                String newId;
                do {
                    newId = String.format("REQ-%02d", autoIndex++);
                } while (usedIds.contains(newId));
                usedIds.add(newId);
                result.add(new Requirement(newId, r.category(), r.item(), r.description(), r.importance()));
            }
        }
        return result;
    }

    private List<Requirement> parseRequirements(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        try {
            String json = content.trim();
            // 코드블록 감싸기 제거
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            return objectMapper.readValue(json, REQ_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse requirements JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private List<List<String>> splitIntoBatches(List<String> chunks) {
        List<List<String>> batches = new ArrayList<>();
        List<String> currentBatch = new ArrayList<>();
        int currentSize = 0;

        for (String chunk : chunks) {
            if (currentSize + chunk.length() > BATCH_CHAR_LIMIT && !currentBatch.isEmpty()) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentSize = 0;
            }
            currentBatch.add(chunk);
            currentSize += chunk.length();
        }
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }
        return batches;
    }
}
