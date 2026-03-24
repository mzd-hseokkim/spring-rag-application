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
import java.util.Comparator;
import java.util.List;

@Service
public class RequirementExtractor {

    private static final Logger log = LoggerFactory.getLogger(RequirementExtractor.class);
    private static final int BATCH_CHAR_LIMIT = 50_000;
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
        String documentContent = String.join(CHUNK_SEPARATOR, customerChunks);
        String input = userInput != null ? userInput : "";

        List<Requirement> requirements;
        if (documentContent.length() <= BATCH_CHAR_LIMIT) {
            requirements = extractFromContent(documentContent, input);
        } else {
            requirements = extractWithMapReduce(customerChunks, input, callback);
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
                                                    QuestionnaireGeneratorService.ProgressCallback callback) {
        // Map: 배치별 요구사항 추출
        List<List<String>> batches = splitIntoBatches(chunks);
        List<Requirement> allRequirements = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            callback.onProgress("요구사항 추출 중... (" + (i + 1) + "/" + batches.size() + " 배치)");
            String batchContent = String.join(CHUNK_SEPARATOR, batches.get(i));
            List<Requirement> partial = extractFromContent(batchContent, userInput);
            allRequirements.addAll(partial);
            log.info("Batch {}/{}: extracted {} requirements", i + 1, batches.size(), partial.size());
        }

        // Reduce: 중복 제거 및 ID 재부여
        callback.onProgress("요구사항을 정리하고 있습니다...");
        return deduplicateAndReindex(allRequirements);
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

        // ID 재부여
        List<Requirement> result = new ArrayList<>();
        for (int i = 0; i < deduplicated.size(); i++) {
            Requirement r = deduplicated.get(i);
            result.add(new Requirement(
                    String.format("REQ-%02d", i + 1),
                    r.category(), r.item(), r.description(), r.importance()));
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
