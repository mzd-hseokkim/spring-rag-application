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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

@Service
public class OutlineExtractor {

    private static final Logger log = LoggerFactory.getLogger(OutlineExtractor.class);
    private static final TypeReference<List<OutlineNode>> OUTLINE_LIST_TYPE = new TypeReference<>() {};
    private static final int REQ_SUMMARY_CHAR_LIMIT = 12_000;
    private static final int MAX_PARALLEL = 3;

    private final ModelClientProvider modelClientProvider;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public OutlineExtractor(ModelClientProvider modelClientProvider,
                             PromptLoader promptLoader,
                             ObjectMapper objectMapper) {
        this.modelClientProvider = modelClientProvider;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
    }

    public List<OutlineNode> extract(List<String> customerChunks, String userInput) {
        return extract(customerChunks, userInput, "", List.of(), null);
    }

    public List<OutlineNode> extract(List<String> customerChunks, String userInput, String requirementsSummary) {
        return extract(customerChunks, userInput, requirementsSummary, List.of(), null);
    }

    /**
     * 요구사항 목록이 프롬프트에 들어갈 수 없을 만큼 크면 map-reduce로 처리.
     * Map: 요구사항 배치별로 "이 요구사항을 반영할 목차 섹션을 제안하라"
     * Reduce: 모든 제안 + 문서 내용 → 최종 통합 목차
     */
    public List<OutlineNode> extract(List<String> customerChunks, String userInput,
                                      String requirementsSummary, List<Requirement> requirements,
                                      ProgressCallback progressCallback) {
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
                    throw new RuntimeException(e);
                }
            }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Reduce: 모든 제안을 합쳐서 최종 목차 생성
        if (progressCallback != null) {
            progressCallback.onProgress("목차를 통합하고 있습니다...");
        }
        String combinedSuggestions = String.join("\n\n---\n\n", allSuggestions);
        return reduceOutline(customerChunks, userInput, combinedSuggestions);
    }

    /**
     * Map 단계: 요구사항 배치에 대한 목차 섹션 제안
     */
    private String mapOutlineSuggestion(String requirementsText, int batchNum, int totalBatches) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);

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
    private List<OutlineNode> reduceOutline(List<String> customerChunks, String userInput, String rawSuggestions) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);

        String rawContent = String.join("\n---\n", customerChunks);
        if (rawContent.length() > 20_000) {
            rawContent = rawContent.substring(0, 20_000) + "\n... (이하 생략)";
        }
        final String suggestions = rawSuggestions.length() > 20_000
                ? rawSuggestions.substring(0, 20_000) + "\n... (이하 생략)"
                : rawSuggestions;

        // Pass 1: 대분류만 생성
        String pass1Prompt = """
                다음 고객 문서와 요구사항 분석 결과를 바탕으로, 제안서의 **대분류(1단계) 목차**만 생성하세요.

                ## 고객 문서 (요약)
                %s

                ## 추가 지시사항
                %s

                ## 요구사항 분석 결과 (목차 섹션 제안)
                %s

                ## 출력 규칙
                - 대분류 7~12개 생성 (하위 항목은 생성하지 마세요)
                - 각 대분류에 description으로 어떤 내용/요구사항이 포함되어야 하는지 상세히 기술
                - children은 빈 배열 []로 두세요
                - 반드시 JSON 배열로만 응답 (다른 텍스트 없이)

                ## 필수 포함 주제 (하나라도 빠지면 안 됩니다)
                아래 주제들이 반드시 대분류에 포함되어야 합니다:
                - 사업 이해/개요 (배경, 목적, 범위)
                - 기술/시스템 아키텍처
                - 주요 기능 구현
                - 데이터 구축/관리
                - 보안 및 컴플라이언스
                - 프로젝트 관리/수행 방안
                - 투입 인력/조직
                - 인수인계, 기술이전, 유지보수, 교육훈련
                - 유사 수행 실적
                위 주제 외에도 요구사항 분석에서 제안된 주제가 있으면 추가하세요.

                [{"key":"1","title":"대분류 제목","description":"포함할 내용 상세 설명","children":[]}]
                """.formatted(rawContent, userInput != null ? userInput : "", suggestions);

        String pass1Content = client.prompt().user(pass1Prompt).call().content();
        List<OutlineNode> topLevel = parseOutline(pass1Content);
        log.info("Outline reduce pass 1: {} top-level sections", topLevel.size());

        if (topLevel.isEmpty()) return topLevel;

        // Pass 2: 대분류별로 하위 구조 생성 (병렬)
        List<OutlineNode> finalOutline = java.util.Collections.synchronizedList(new ArrayList<>());
        Semaphore sem = new Semaphore(MAX_PARALLEL);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (OutlineNode top : topLevel) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    sem.acquire();
                    try {
                        OutlineNode expanded = expandSection(client, top, suggestions);
                        finalOutline.add(expanded);
                    } finally {
                        sem.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // key 순서대로 정렬
        finalOutline.sort(OutlineExtractor::compareKeys);
        log.info("Outline reduce complete: {} top-level sections with children", finalOutline.size());
        return finalOutline;
    }

    /**
     * 대분류 하나를 받아서 중분류/소분류(2~3단계)를 생성
     */
    private OutlineNode expandSection(ChatClient client, OutlineNode topSection, String suggestions) {
        String expandPrompt = """
                다음 제안서 대분류 항목의 하위 목차(중분류, 소분류)를 구성하세요.
                이 제안서는 **프레젠테이션형 문서**이므로 하나의 리프 항목 = 하나의 장표(슬라이드)입니다.

                ## 대분류
                - key: %s
                - title: %s
                - description: %s

                ## 참고할 요구사항 분석 결과
                %s

                ## 출력 규칙
                - 이 대분류의 하위 구조(중분류 > 소분류)를 JSON으로 생성
                - 중분류 2~5개, 소분류는 필요 시 중분류당 2~5개
                - key는 "%s.1", "%s.1.1" 형식
                - **구체적인 제목 사용 필수** ("기능 1" 같은 placeholder 금지)
                - 요구사항 ID(SFR-001 등)가 있으면 description에 포함
                - 반드시 JSON 배열로만 응답 (children 포함한 중분류 배열)

                [{"key":"%s.1","title":"중분류 제목","description":"설명","children":[]}]
                """.formatted(topSection.key(), topSection.title(), topSection.description(),
                suggestions.length() > 8_000 ? suggestions.substring(0, 8_000) : suggestions,
                topSection.key(), topSection.key(), topSection.key());

        String content = client.prompt().user(expandPrompt).call().content();
        List<OutlineNode> children = parseOutline(content);
        log.info("Expanded section '{}': {} children", topSection.key(), children.size());

        return new OutlineNode(topSection.key(), topSection.title(), topSection.description(), children);
    }

    /**
     * 요구사항이 적을 때: 단일 호출로 목차 추출
     */
    private List<OutlineNode> extractDirect(List<String> customerChunks, String userInput, String reqs) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);
        String prompt = promptLoader.load("generation-extract-outline.txt");

        String rawContent = String.join("\n---\n", customerChunks);
        int contentLimit = reqs.length() > 500 ? 25_000 : 50_000;
        if (rawContent.length() > contentLimit) {
            rawContent = rawContent.substring(0, contentLimit) + "\n... (이하 생략)";
        }
        final String documentContent = rawContent;
        String input = userInput != null ? userInput : "";

        log.info("Outline direct prompt sizes: document={}chars, requirements={}chars", documentContent.length(), reqs.length());

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
            throw new RuntimeException("Failed to serialize outline", e);
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
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String message);
    }
}
