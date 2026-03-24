package com.example.rag.questionnaire.workflow;

import com.example.rag.common.PromptLoader;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import com.example.rag.search.ChunkSearchResult;
import com.example.rag.search.SearchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RequirementMatcher {

    private static final Logger log = LoggerFactory.getLogger(RequirementMatcher.class);
    private static final int LLM_BATCH_SIZE = 10;
    private static final String CHUNK_SEPARATOR = "\n---\n";
    private static final TypeReference<List<MatchResult>> MATCH_LIST_TYPE = new TypeReference<>() {};

    private final SearchService searchService;
    private final ModelClientProvider modelClientProvider;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public RequirementMatcher(SearchService searchService,
                               ModelClientProvider modelClientProvider,
                               PromptLoader promptLoader,
                               ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.modelClientProvider = modelClientProvider;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
    }

    /**
     * RAG 모드: 각 요구사항을 쿼리로 제안서를 검색하여 대응 여부 판정
     */
    public GapMatrix matchWithRag(List<Requirement> requirements, List<UUID> proposalDocIds,
                                   QuestionnaireGeneratorService.ProgressCallback callback) {
        List<CompletableFuture<GapMatrix.GapEntry>> futures = new ArrayList<>();

        for (int i = 0; i < requirements.size(); i++) {
            Requirement req = requirements.get(i);
            int index = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                String query = req.item() + " " + req.description();
                if (query.length() > 500) query = query.substring(0, 500);
                List<ChunkSearchResult> results = searchService.searchDirect(query, proposalDocIds);

                if (results.isEmpty()) {
                    return new GapMatrix.GapEntry(req, "누락", "", "제안서에서 관련 내용을 찾을 수 없음");
                }

                String proposalContent = results.stream()
                        .map(ChunkSearchResult::contextContent)
                        .collect(Collectors.joining(" / "));
                if (proposalContent.length() > 500) {
                    proposalContent = proposalContent.substring(0, 500) + "...";
                }

                // 검색 결과가 있으면 "부분충족"으로 기본 판정 (RAG는 정밀 판정 불가)
                return new GapMatrix.GapEntry(req, "부분충족", proposalContent, "RAG 검색으로 관련 내용 발견 — 상세 확인 필요");
            }));

            if ((index + 1) % 5 == 0 || index == requirements.size() - 1) {
                callback.onProgress("제안서 대응 확인 중... (" + (index + 1) + "/" + requirements.size() + " 항목)");
            }
        }

        List<GapMatrix.GapEntry> entries = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        String summary = buildSummary(entries);
        log.info("RAG matching complete: {} entries (충족:{}, 부분충족:{}, 누락:{})",
                entries.size(),
                entries.stream().filter(e -> "충족".equals(e.matchStatus())).count(),
                entries.stream().filter(e -> "부분충족".equals(e.matchStatus())).count(),
                entries.stream().filter(e -> "누락".equals(e.matchStatus())).count());

        return new GapMatrix(entries, summary);
    }

    /**
     * LLM 모드: 요구사항 + 제안서 청크를 LLM에 넘겨 정밀 판정
     */
    public GapMatrix matchWithLlm(List<Requirement> requirements, List<String> proposalChunks,
                                    QuestionnaireGeneratorService.ProgressCallback callback) {
        String proposalContent = String.join(CHUNK_SEPARATOR, proposalChunks);
        if (proposalContent.length() > 30_000) {
            proposalContent = proposalContent.substring(0, 30_000) + "\n... (이하 생략)";
        }

        // 요구사항을 배치로 나눠서 LLM 호출
        List<GapMatrix.GapEntry> allEntries = new ArrayList<>();
        Map<String, Requirement> reqMap = requirements.stream()
                .collect(Collectors.toMap(Requirement::id, Function.identity()));

        for (int i = 0; i < requirements.size(); i += LLM_BATCH_SIZE) {
            int end = Math.min(i + LLM_BATCH_SIZE, requirements.size());
            List<Requirement> batch = requirements.subList(i, end);
            callback.onProgress("제안서 대응 확인 중 (LLM)... (" + end + "/" + requirements.size() + " 항목)");

            List<MatchResult> results = matchBatchWithLlm(batch, proposalContent);

            for (MatchResult result : results) {
                Requirement req = reqMap.get(result.requirementId());
                if (req != null) {
                    allEntries.add(new GapMatrix.GapEntry(
                            req, result.matchStatus(), result.proposalContent(), result.gapDescription()));
                }
            }

            log.info("LLM matching batch {}-{}/{} complete", i + 1, end, requirements.size());
        }

        // 매칭되지 않은 요구사항은 "누락"으로
        java.util.Set<String> matchedIds = allEntries.stream()
                .map(e -> e.requirement().id())
                .collect(Collectors.toSet());
        for (Requirement req : requirements) {
            if (!matchedIds.contains(req.id())) {
                allEntries.add(new GapMatrix.GapEntry(req, "누락", "", "LLM 매칭 결과에 포함되지 않음"));
            }
        }

        // 원래 요구사항 순서 유지
        List<String> idOrder = requirements.stream().map(Requirement::id).toList();
        allEntries.sort(Comparator.comparingInt(e -> idOrder.indexOf(e.requirement().id())));

        String summary = buildSummary(allEntries);
        log.info("LLM matching complete: {} entries", allEntries.size());
        return new GapMatrix(allEntries, summary);
    }

    /**
     * 제안서 없는 경우: 모든 요구사항을 "미확인"으로 설정
     */
    public GapMatrix buildWithoutProposal(List<Requirement> requirements) {
        List<GapMatrix.GapEntry> entries = requirements.stream()
                .map(req -> new GapMatrix.GapEntry(req, "미확인", "", "제안 문서 미제공"))
                .toList();

        String summary = "제안 문서가 제공되지 않아 대응 확인이 불가합니다. "
                + requirements.size() + "개 요구사항이 추출되었으며, "
                + "이를 기반으로 예상 질문을 생성합니다.";
        return new GapMatrix(entries, summary);
    }

    private List<MatchResult> matchBatchWithLlm(List<Requirement> batch, String proposalContent) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);
        String prompt = promptLoader.load("questionnaire-match-requirement.txt");

        String reqJson;
        try {
            reqJson = objectMapper.writeValueAsString(batch);
        } catch (Exception e) {
            log.error("Failed to serialize requirements", e);
            return List.of();
        }

        String content = client.prompt()
                .user(u -> u.text(prompt)
                        .param("requirements", reqJson)
                        .param("proposalContent", proposalContent))
                .call()
                .content();

        return parseMatchResults(content);
    }

    private List<MatchResult> parseMatchResults(String content) {
        if (content == null || content.isBlank()) return List.of();
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            return objectMapper.readValue(json, MATCH_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse match results JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildSummary(List<GapMatrix.GapEntry> entries) {
        long total = entries.size();
        long met = entries.stream().filter(e -> "충족".equals(e.matchStatus())).count();
        long partial = entries.stream().filter(e -> "부분충족".equals(e.matchStatus())).count();
        long weak = entries.stream().filter(e -> "미흡".equals(e.matchStatus())).count();
        long missing = entries.stream().filter(e -> "누락".equals(e.matchStatus())).count();
        long unknown = entries.stream().filter(e -> "미확인".equals(e.matchStatus())).count();

        return "총 %d개 요구사항 중 충족 %d개, 부분충족 %d개, 미흡 %d개, 누락 %d개%s."
                .formatted(total, met, partial, weak, missing,
                        unknown > 0 ? ", 미확인 " + unknown + "개" : "");
    }

    private record MatchResult(
            String requirementId,
            String matchStatus,
            String proposalContent,
            String gapDescription
    ) {}
}
