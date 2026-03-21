package com.example.rag.chat;

import com.example.rag.agent.AgentDecision;
import com.example.rag.agent.AgentStepEvent;
import com.example.rag.agent.MultiStepReasoner;
import com.example.rag.agent.SearchAgent;
import com.example.rag.common.PromptLoader;
import com.example.rag.conversation.ConversationManagementService;
import com.example.rag.conversation.ConversationMessage;
import com.example.rag.conversation.ConversationService;
import com.example.rag.evaluation.EvaluationService;
import com.example.rag.observability.PipelineTracer;
import com.example.rag.observability.TraceContext;
import com.example.rag.search.ChunkSearchResult;
import com.example.rag.search.SearchService;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import com.example.rag.search.query.QueryCompressor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final String ragSystemPrompt;
    private final String generalSystemPrompt;

    private final ModelClientProvider modelProvider;
    private final SearchService searchService;
    private final ConversationService conversationService;
    private final ConversationManagementService conversationManagementService;
    private final QueryCompressor queryCompressor;
    private final SearchAgent searchAgent;
    private final MultiStepReasoner multiStepReasoner;
    private final PipelineTracer pipelineTracer;
    private final EvaluationService evaluationService;

    public ChatService(ModelClientProvider modelProvider,
                       SearchService searchService,
                       ConversationService conversationService,
                       ConversationManagementService conversationManagementService,
                       QueryCompressor queryCompressor,
                       SearchAgent searchAgent,
                       MultiStepReasoner multiStepReasoner,
                       PipelineTracer pipelineTracer,
                       EvaluationService evaluationService,
                       PromptLoader promptLoader) {
        this.modelProvider = modelProvider;
        this.searchService = searchService;
        this.conversationService = conversationService;
        this.conversationManagementService = conversationManagementService;
        this.queryCompressor = queryCompressor;
        this.searchAgent = searchAgent;
        this.multiStepReasoner = multiStepReasoner;
        this.pipelineTracer = pipelineTracer;
        this.evaluationService = evaluationService;
        this.ragSystemPrompt = promptLoader.load("rag-system.txt");
        this.generalSystemPrompt = promptLoader.load("general-system.txt");
    }

    private ChatClient chatClient(String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            return modelProvider.getChatClient(java.util.UUID.fromString(modelId));
        }
        return modelProvider.getChatClient(ModelPurpose.CHAT);
    }

    public ChatResponse chat(String sessionId, String message, String modelId, UUID userId,
                             boolean includePublicDocs, List<UUID> tagIds, List<UUID> collectionIds,
                             Consumer<AgentStepEvent> stepCallback) {
        TraceContext trace = new TraceContext(sessionId, message);
        List<AgentStepEvent> agentSteps = new ArrayList<>();
        Consumer<AgentStepEvent> callback = event -> {
            agentSteps.add(event);
            stepCallback.accept(event);
        };

        // 대화 레코드 자동 생성 (첫 메시지 시)
        boolean isFirstMessage = conversationService.getHistory(sessionId).isEmpty();
        conversationManagementService.getOrCreate(sessionId, modelId, userId);

        conversationService.addMessage(sessionId, ConversationMessage.user(message));

        // 대화 압축
        trace.startStep("compress");
        callback.accept(new AgentStepEvent("compress", "질문 분석 중..."));
        String searchQuery = queryCompressor.compress(sessionId, message);
        trace.endStep(Map.of("compressed", !searchQuery.equals(message)));

        // Agent 판단
        trace.startStep("decide");
        callback.accept(new AgentStepEvent("decide", "행동 결정 중..."));
        AgentDecision decision = searchAgent.decide(searchQuery, userId, includePublicDocs, tagIds, collectionIds);
        trace.endStep(Map.of("action", decision.action().name(),
                "targetDocs", decision.targetDocumentIds().size()));

        ChatResponse result = switch (decision.action()) {
            case DIRECT_ANSWER -> {
                callback.accept(new AgentStepEvent("direct", "직접 답변 생성 중..."));
                pipelineTracer.logTrace(trace);
                yield chatGeneral(sessionId, message, modelId);
            }
            case CLARIFY -> {
                callback.accept(new AgentStepEvent("clarify", "질문 명확화 요청 중..."));
                pipelineTracer.logTrace(trace);
                yield chatClarify(sessionId, message, modelId);
            }
            case SEARCH -> {
                callback.accept(new AgentStepEvent("decompose", "질문 복잡도 분석 중..."));
                trace.startStep("decompose");
                List<String> subQueries = multiStepReasoner.decompose(searchQuery);
                trace.endStep(Map.of("isMultiStep", subQueries != null));

                if (subQueries != null && subQueries.size() > 1) {
                    yield chatMultiStep(sessionId, message, searchQuery, subQueries,
                            decision.targetDocumentIds(), modelId, trace, callback);
                } else {
                    callback.accept(new AgentStepEvent("search", "문서 검색 중..."));
                    yield chatWithRag(sessionId, message, searchQuery,
                            decision.targetDocumentIds(), modelId, trace, callback);
                }
            }
        };

        // 응답 완료 시 대화 메타데이터 갱신 (제목 생성 + updatedAt)
        StringBuilder titleCapture = new StringBuilder();
        Flux<String> wrappedTokens = result.tokens()
                .doOnNext(titleCapture::append)
                .doOnComplete(() -> {
                    conversationManagementService.touch(sessionId);
                    if (isFirstMessage) {
                        conversationManagementService.generateTitle(sessionId, message, titleCapture.toString());
                    }
                });

        return new ChatResponse(wrappedTokens, result.sources(), result.sourceRefs());
    }

    private ChatResponse chatWithRag(String sessionId, String message, String searchQuery,
                                      List<UUID> documentIds, String modelId,
                                      TraceContext trace, Consumer<AgentStepEvent> callback) {
        trace.startStep("search");
        List<ChunkSearchResult> searchResults = searchService.search(searchQuery, documentIds);
        trace.endStep(Map.of("resultCount", searchResults.size()));

        callback.accept(new AgentStepEvent("generate", "답변 생성 중..."));

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

        List<SourceInfo> sources = searchResults.stream()
                .map(r -> new SourceInfo(r.documentId().toString(), r.filename(),
                        r.chunkIndex(), truncate(r.content(), 100)))
                .toList();
        List<ConversationMessage.SourceRef> sourceRefs = toSourceRefs(sources);

        StringBuilder fullResponse = new StringBuilder();

        trace.startStep("generate");
        Flux<String> tokenStream = chatClient(modelId).prompt()
                .system(ragSystemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    trace.endStep(Map.of("responseLength", fullResponse.length()));
                    pipelineTracer.logTrace(trace);
                    conversationService.addMessage(sessionId,
                            ConversationMessage.assistant(fullResponse.toString(), sourceRefs));
                    evaluationService.evaluateIfSampled(message, context, fullResponse.toString());
                });

        return new ChatResponse(tokenStream, sources, sourceRefs);
    }

    private ChatResponse chatMultiStep(String sessionId, String message, String searchQuery,
                                        List<String> subQueries, List<UUID> documentIds,
                                        String modelId, TraceContext trace,
                                        Consumer<AgentStepEvent> callback) {
        trace.startStep("multi_step");
        var result = multiStepReasoner.reason(searchQuery, subQueries, documentIds, callback);
        trace.endStep(Map.of("subQueryCount", subQueries.size()));

        List<SourceInfo> sources = result.searchResults().stream()
                .map(r -> new SourceInfo(r.documentId().toString(), r.filename(),
                        r.chunkIndex(), truncate(r.content(), 100)))
                .toList();
        List<ConversationMessage.SourceRef> sourceRefs = toSourceRefs(sources);

        StringBuilder fullResponse = new StringBuilder();

        Flux<String> tokenStream = result.tokens()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    pipelineTracer.logTrace(trace);
                    conversationService.addMessage(sessionId,
                            ConversationMessage.assistant(fullResponse.toString(), sourceRefs));
                });

        return new ChatResponse(tokenStream, sources, sourceRefs);
    }

    private ChatResponse chatGeneral(String sessionId, String message, String modelId) {
        String history = buildHistory(sessionId);
        String userPrompt = history.equals("(없음)") ? message
                : "[이전 대화]\n%s\n\n질문: %s".formatted(history, message);

        StringBuilder fullResponse = new StringBuilder();

        Flux<String> tokenStream = chatClient(modelId).prompt()
                .system(generalSystemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> conversationService.addMessage(sessionId,
                        ConversationMessage.assistant(fullResponse.toString())));

        return new ChatResponse(tokenStream, List.of(), List.of());
    }

    private ChatResponse chatClarify(String sessionId, String message, String modelId) {
        String prompt = "사용자의 질문이 모호합니다. 질문을 더 구체적으로 해달라고 정중하게 요청하세요.\n\n질문: " + message;

        StringBuilder fullResponse = new StringBuilder();

        Flux<String> tokenStream = chatClient(modelId).prompt()
                .system(generalSystemPrompt)
                .user(prompt)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> conversationService.addMessage(sessionId,
                        ConversationMessage.assistant(fullResponse.toString())));

        return new ChatResponse(tokenStream, List.of(), List.of());
    }

    private String buildContext(List<ChunkSearchResult> results) {
        return results.stream()
                .map(r -> "[출처: %s, 청크 %d]\n%s".formatted(r.filename(), r.chunkIndex(), r.contextContent()))
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

    private List<ConversationMessage.SourceRef> toSourceRefs(List<SourceInfo> sources) {
        return sources.stream()
                .map(s -> new ConversationMessage.SourceRef(s.documentId(), s.filename(), s.chunkIndex(), s.excerpt()))
                .toList();
    }

    public record ChatResponse(Flux<String> tokens, List<SourceInfo> sources, List<ConversationMessage.SourceRef> sourceRefs) {}

    public record SourceInfo(String documentId, String filename, int chunkIndex, String excerpt) {}
}
