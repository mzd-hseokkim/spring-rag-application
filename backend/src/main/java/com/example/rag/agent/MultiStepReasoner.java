package com.example.rag.agent;

import com.example.rag.common.PromptLoader;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import com.example.rag.search.ChunkSearchResult;
import com.example.rag.search.SearchService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class MultiStepReasoner {

    private final String decomposePrompt;
    private final String synthesizePrompt;
    private final ModelClientProvider modelProvider;
    private final SearchService searchService;
    private final int maxSubQueries;

    public MultiStepReasoner(ModelClientProvider modelProvider,
                             PromptLoader promptLoader,
                             SearchService searchService,
                             @Value("${app.rag.max-sub-queries:3}") int maxSubQueries) {
        this.modelProvider = modelProvider;
        this.decomposePrompt = promptLoader.load("decompose.txt");
        this.synthesizePrompt = promptLoader.load("synthesize.txt");
        this.searchService = searchService;
        this.maxSubQueries = maxSubQueries;
    }

    private ChatClient chatClient() {
        return modelProvider.getChatClient(ModelPurpose.QUERY);
    }

    public List<String> decompose(String query) {
        String response = chatClient().prompt()
                .user(decomposePrompt.formatted(query))
                .call()
                .content()
                .trim();

        if (response.toUpperCase().contains("SINGLE")) {
            return null;
        }

        return Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(maxSubQueries)
                .toList();
    }

    /**
     * 병렬 검색 + 종합 생성.
     * 서브쿼리를 병렬로 검색한 뒤, 모든 결과를 합쳐서 한 번에 답변을 생성한다.
     */
    public ReasonResult reason(String originalQuery, List<String> subQueries,
                               List<UUID> documentIds,
                               Consumer<AgentStepEvent> stepCallback) {

        // 서브쿼리 수 제한
        List<String> limitedQueries = subQueries.size() > maxSubQueries
                ? subQueries.subList(0, maxSubQueries) : subQueries;

        stepCallback.accept(new AgentStepEvent("search",
                "%d개 하위 질문 병렬 검색 중...".formatted(limitedQueries.size())));

        // 병렬 검색
        Map<UUID, ChunkSearchResult> allResults = new ConcurrentHashMap<>();
        List<CompletableFuture<List<ChunkSearchResult>>> futures = limitedQueries.stream()
                .map(subQuery -> CompletableFuture.supplyAsync(() ->
                        searchService.search(subQuery, documentIds)))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        for (var future : futures) {
            future.join().forEach(r -> allResults.putIfAbsent(r.chunkId(), r));
        }

        stepCallback.accept(new AgentStepEvent("generate",
                "%d개 검색 결과로 답변 종합 중...".formatted(allResults.size())));

        String fullContext = allResults.values().stream()
                .map(ChunkSearchResult::contextContent)
                .collect(Collectors.joining("\n\n"));

        // 서브쿼리 목록을 컨텍스트에 포함
        String subQuerySummary = limitedQueries.stream()
                .map(q -> "- " + q)
                .collect(Collectors.joining("\n"));

        Flux<String> tokenStream = chatClient().prompt()
                .user(synthesizePrompt.formatted(originalQuery, fullContext, subQuerySummary))
                .stream()
                .content();

        return new ReasonResult(tokenStream, new ArrayList<>(allResults.values()));
    }

    public record ReasonResult(Flux<String> tokens, List<ChunkSearchResult> searchResults) {}
}
