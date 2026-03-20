package com.example.rag.agent;

import com.example.rag.common.PromptLoader;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import com.example.rag.search.ChunkSearchResult;
import com.example.rag.search.SearchService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class MultiStepReasoner {

    private final String decomposePrompt;
    private final String synthesizePrompt;
    private final ModelClientProvider modelProvider;
    private final SearchService searchService;

    public MultiStepReasoner(ModelClientProvider modelProvider,
                             PromptLoader promptLoader,
                             SearchService searchService) {
        this.modelProvider = modelProvider;
        this.decomposePrompt = promptLoader.load("decompose.txt");
        this.synthesizePrompt = promptLoader.load("synthesize.txt");
        this.searchService = searchService;
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
                .toList();
    }

    public ReasonResult reason(String originalQuery, List<String> subQueries,
                               Consumer<AgentStepEvent> stepCallback) {
        StringBuilder subAnswers = new StringBuilder();
        Map<UUID, ChunkSearchResult> allResults = new LinkedHashMap<>();

        for (int i = 0; i < subQueries.size(); i++) {
            String subQuery = subQueries.get(i);
            stepCallback.accept(new AgentStepEvent("sub_search",
                    "하위 질문 %d/%d 검색 중: \"%s\"".formatted(i + 1, subQueries.size(), subQuery)));

            List<ChunkSearchResult> results = searchService.search(subQuery);
            results.forEach(r -> allResults.putIfAbsent(r.chunkId(), r));

            String context = results.stream()
                    .map(ChunkSearchResult::contextContent)
                    .collect(Collectors.joining("\n\n"));

            stepCallback.accept(new AgentStepEvent("sub_answer",
                    "하위 질문 %d/%d 답변 생성 중...".formatted(i + 1, subQueries.size())));

            String subAnswer = chatClient().prompt()
                    .user("다음 컨텍스트를 바탕으로 질문에 간단히 답하세요.\n\n[컨텍스트]\n%s\n\n질문: %s"
                            .formatted(context, subQuery))
                    .call()
                    .content()
                    .trim();

            stepCallback.accept(new AgentStepEvent("sub_done",
                    "하위 질문 %d/%d 완료".formatted(i + 1, subQueries.size())));

            subAnswers.append("Q: %s\nA: %s\n\n".formatted(subQuery, subAnswer));
        }

        stepCallback.accept(new AgentStepEvent("synthesize", "답변 종합 중..."));

        // 최종 종합은 스트리밍으로 반환
        Flux<String> tokenStream = chatClient().prompt()
                .user(synthesizePrompt.formatted(originalQuery, subAnswers.toString()))
                .stream()
                .content();

        return new ReasonResult(tokenStream, new ArrayList<>(allResults.values()));
    }

    public record ReasonResult(Flux<String> tokens, List<ChunkSearchResult> searchResults) {}
}
