package com.example.rag.chat;

import com.example.rag.common.PromptLoader;
import com.example.rag.conversation.ConversationMessage;
import com.example.rag.conversation.ConversationService;
import com.example.rag.evaluation.EvaluationService;
import com.example.rag.observability.PipelineTracer;
import com.example.rag.observability.TraceContext;
import com.example.rag.search.ChunkSearchResult;
import com.example.rag.search.SearchService;
import com.example.rag.search.query.QueryCompressor;
import com.example.rag.search.query.QueryRoute;
import com.example.rag.search.query.QueryRouter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final String ragSystemPrompt;
    private final String generalSystemPrompt;

    private final ChatClient chatClient;
    private final SearchService searchService;
    private final ConversationService conversationService;
    private final QueryCompressor queryCompressor;
    private final QueryRouter queryRouter;
    private final PipelineTracer pipelineTracer;
    private final EvaluationService evaluationService;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       SearchService searchService,
                       ConversationService conversationService,
                       QueryCompressor queryCompressor,
                       QueryRouter queryRouter,
                       PipelineTracer pipelineTracer,
                       EvaluationService evaluationService,
                       PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.searchService = searchService;
        this.conversationService = conversationService;
        this.queryCompressor = queryCompressor;
        this.queryRouter = queryRouter;
        this.pipelineTracer = pipelineTracer;
        this.evaluationService = evaluationService;
        this.ragSystemPrompt = promptLoader.load("rag-system.txt");
        this.generalSystemPrompt = promptLoader.load("general-system.txt");
    }

    public ChatResponse chat(String sessionId, String message) {
        TraceContext trace = new TraceContext(sessionId, message);

        // 대화 이력에 사용자 메시지 저장
        conversationService.addMessage(sessionId, ConversationMessage.user(message));

        // 대화 압축
        trace.startStep("compress");
        String searchQuery = queryCompressor.compress(sessionId, message);
        trace.endStep(Map.of("compressed", !searchQuery.equals(message)));

        // 쿼리 라우팅
        trace.startStep("route");
        QueryRoute route = queryRouter.route(searchQuery);
        trace.endStep(Map.of("result", route.name()));

        if (route == QueryRoute.GENERAL) {
            pipelineTracer.logTrace(trace);
            return chatGeneral(sessionId, message);
        }
        return chatWithRag(sessionId, message, searchQuery, trace);
    }

    private ChatResponse chatWithRag(String sessionId, String message, String searchQuery, TraceContext trace) {
        trace.startStep("search");
        List<ChunkSearchResult> searchResults = searchService.search(searchQuery);
        trace.endStep(Map.of("resultCount", searchResults.size()));

        String context = buildContext(searchResults);
        String history = buildHistory(sessionId);
        String userPrompt = """
                ---
                [이전 대화]
                %s
                ---
                [컨텍스트]
                %s
                ---

                질문: %s
                """.formatted(history, context, message);

        StringBuilder fullResponse = new StringBuilder();

        trace.startStep("generate");
        Flux<String> tokenStream = chatClient.prompt()
                .system(ragSystemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    trace.endStep(Map.of("responseLength", fullResponse.length()));
                    pipelineTracer.logTrace(trace);
                    conversationService.addMessage(sessionId,
                            ConversationMessage.assistant(fullResponse.toString()));
                    evaluationService.evaluateIfSampled(message, context, fullResponse.toString());
                });

        List<SourceInfo> sources = searchResults.stream()
                .map(r -> new SourceInfo(r.documentId().toString(), r.filename(),
                        r.chunkIndex(), truncate(r.content(), 100)))
                .toList();

        return new ChatResponse(tokenStream, sources);
    }

    private ChatResponse chatGeneral(String sessionId, String message) {
        String history = buildHistory(sessionId);
        String userPrompt = history.equals("(없음)") ? message
                : "[이전 대화]\n%s\n\n질문: %s".formatted(history, message);

        StringBuilder fullResponse = new StringBuilder();

        Flux<String> tokenStream = chatClient.prompt()
                .system(generalSystemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> conversationService.addMessage(sessionId,
                        ConversationMessage.assistant(fullResponse.toString())));

        return new ChatResponse(tokenStream, List.of());
    }

    private String buildContext(List<ChunkSearchResult> results) {
        return results.stream()
                .map(r -> "[출처: %s, 청크 %d]\n%s".formatted(r.filename(), r.chunkIndex(), r.content()))
                .collect(Collectors.joining("\n\n"));
    }

    private String buildHistory(String sessionId) {
        List<ConversationMessage> history = conversationService.getHistory(sessionId);
        if (history.isEmpty()) return "(없음)";

        List<ConversationMessage> previous = history.subList(0, Math.max(0, history.size() - 1));
        if (previous.isEmpty()) return "(없음)";

        return previous.stream()
                .map(m -> "%s: %s".formatted(m.role(), m.content()))
                .collect(Collectors.joining("\n"));
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    public record ChatResponse(Flux<String> tokens, List<SourceInfo> sources) {}

    public record SourceInfo(String documentId, String filename, int chunkIndex, String excerpt) {}
}
