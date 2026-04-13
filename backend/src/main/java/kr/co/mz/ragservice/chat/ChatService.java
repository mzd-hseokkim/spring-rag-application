package kr.co.mz.ragservice.chat;

import kr.co.mz.ragservice.agent.*;
import kr.co.mz.ragservice.common.PromptLoader;
import kr.co.mz.ragservice.questionnaire.workflow.TavilySearchService;
import kr.co.mz.ragservice.questionnaire.workflow.WebSearchException;
import kr.co.mz.ragservice.conversation.ConversationManagementService;
import kr.co.mz.ragservice.conversation.ConversationMessage;
import kr.co.mz.ragservice.conversation.ConversationService;
import kr.co.mz.ragservice.evaluation.EvaluationService;
import kr.co.mz.ragservice.observability.PipelineTracer;
import kr.co.mz.ragservice.observability.TraceContext;
import kr.co.mz.ragservice.search.ChunkSearchResult;
import kr.co.mz.ragservice.search.SearchService;
import kr.co.mz.ragservice.model.ModelClientProvider;
import kr.co.mz.ragservice.model.ModelPurpose;
import kr.co.mz.ragservice.search.query.QueryCompressor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
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

    private static final String STEP_GENERATE = "generate";
    private static final String STEP_SEARCH = "search";
    private static final String ACTION_DIRECT_ANSWER = "DIRECT_ANSWER";
    private static final String MSG_GENERATING_DIRECT = "직접 답변 생성 중...";

    private final String ragSystemPrompt;
    private final String generalSystemPrompt;
    private final String clarifyPrompt;
    private final String webSearchSystemPrompt;

    private final ModelClientProvider modelProvider;
    private final SearchService searchService;
    private final ConversationService conversationService;
    private final ConversationManagementService conversationManagementService;
    private final QueryCompressor queryCompressor;
    private final SearchAgent searchAgent;
    private final MultiStepReasoner multiStepReasoner;
    private final PipelineTracer pipelineTracer;
    private final EvaluationService evaluationService;
    private final kr.co.mz.ragservice.dashboard.TokenUsageRepository tokenUsageRepository;
    private final TavilySearchService tavilySearchService;
    private final boolean mergeAnalysis;

    public ChatService(ModelClientProvider modelProvider,
                       SearchService searchService,
                       ConversationService conversationService,
                       ConversationManagementService conversationManagementService,
                       QueryCompressor queryCompressor,
                       SearchAgent searchAgent,
                       MultiStepReasoner multiStepReasoner,
                       PipelineTracer pipelineTracer,
                       EvaluationService evaluationService,
                       kr.co.mz.ragservice.dashboard.TokenUsageRepository tokenUsageRepository,
                       TavilySearchService tavilySearchService,
                       PromptLoader promptLoader,
                       @Value("${app.rag.merge-analysis:true}") boolean mergeAnalysis) {
        this.modelProvider = modelProvider;
        this.searchService = searchService;
        this.conversationService = conversationService;
        this.conversationManagementService = conversationManagementService;
        this.queryCompressor = queryCompressor;
        this.searchAgent = searchAgent;
        this.multiStepReasoner = multiStepReasoner;
        this.pipelineTracer = pipelineTracer;
        this.evaluationService = evaluationService;
        this.tokenUsageRepository = tokenUsageRepository;
        this.tavilySearchService = tavilySearchService;
        this.ragSystemPrompt = promptLoader.load("rag-system.txt");
        this.generalSystemPrompt = promptLoader.load("general-system.txt");
        this.clarifyPrompt = promptLoader.load("clarify.txt");
        this.webSearchSystemPrompt = promptLoader.load("web-search-system.txt");
        this.mergeAnalysis = mergeAnalysis;
    }

    private String resolveModelName(String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            return modelProvider.getModelName(UUID.fromString(modelId));
        }
        return modelProvider.getDefaultModelName(ModelPurpose.CHAT);
    }

    private ChatClient chatClient(String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            return modelProvider.getChatClient(UUID.fromString(modelId));
        }
        return modelProvider.getChatClient(ModelPurpose.CHAT);
    }

    @SuppressWarnings("java:S107")
    public ChatResponse chat(String sessionId, String message, String modelId, UUID userId,
                             boolean includePublicDocs, List<UUID> tagIds, List<UUID> collectionIds,
                             boolean enableWebSearch, Consumer<AgentStepEvent> stepCallback) {
        TraceContext trace = new TraceContext(sessionId, message);
        List<AgentStepEvent> agentSteps = new ArrayList<>();
        Consumer<AgentStepEvent> callback = event -> {
            agentSteps.add(event);
            stepCallback.accept(event);
        };

        boolean isFirstMessage = conversationService.getHistory(sessionId).isEmpty();
        conversationManagementService.getOrCreate(sessionId, modelId, userId);
        conversationService.addMessage(sessionId, ConversationMessage.user(message));

        ChatResponse result;

        if (mergeAnalysis) {
            result = chatMergedPipeline(sessionId, message, modelId, userId,
                    includePublicDocs, tagIds, collectionIds, enableWebSearch, trace, callback);
        } else {
            result = chatLegacyPipeline(sessionId, message, modelId, userId,
                    includePublicDocs, tagIds, collectionIds, trace, callback);
        }

        // 응답 완료 시 대화 메타데이터 갱신 + 토큰 사용량 저장
        StringBuilder titleCapture = new StringBuilder();
        String chatModelName = resolveModelName(modelId);
        Flux<String> wrappedTokens = result.tokens()
                .doOnNext(titleCapture::append)
                .doOnComplete(() -> {
                    conversationManagementService.touch(sessionId);
                    if (isFirstMessage) {
                        conversationManagementService.generateTitle(sessionId, message, titleCapture.toString());
                    }
                    try {
                        int inputTokens = message.length() / 4;
                        int outputTokens = titleCapture.length() / 4;
                        tokenUsageRepository.save(new kr.co.mz.ragservice.dashboard.TokenUsageEntity(
                                userId, chatModelName, "CHAT", inputTokens, outputTokens, sessionId));
                    } catch (Exception e) {
                        // 토큰 저장 실패는 무시
                    }
                });

        return new ChatResponse(wrappedTokens, result.sources(), result.sourceRefs());
    }

    // ========== 3단계 통합 파이프라인 (merge-analysis: true) ==========

    @SuppressWarnings("java:S107") // internal pipeline method — parameter object adds unnecessary indirection
    private ChatResponse chatMergedPipeline(String sessionId, String message, String modelId,
                                             UUID userId, boolean includePublicDocs,
                                             List<UUID> tagIds, List<UUID> collectionIds,
                                             boolean enableWebSearch,
                                             TraceContext trace, Consumer<AgentStepEvent> callback) {
        // Stage 1: 분석 (compress + decide + decompose 통합)
        trace.startStep("analyze");
        callback.accept(new AgentStepEvent("analyze", "질문 분석 중..."));
        AnalysisResult analysis = searchAgent.analyze(
                sessionId, message, userId, includePublicDocs, tagIds, collectionIds, enableWebSearch);
        trace.endStep(Map.of("action", analysis.action().name(),
                "searchQuery", analysis.searchQuery(),
                "isMultiStep", analysis.isMultiStep()));

        return switch (analysis.action()) {
            case DIRECT_ANSWER -> {
                callback.accept(new AgentStepEvent(STEP_GENERATE, MSG_GENERATING_DIRECT));
                pipelineTracer.logTrace(trace, userId, ACTION_DIRECT_ANSWER);
                yield chatGeneral(sessionId, message, modelId);
            }
            case CLARIFY -> {
                callback.accept(new AgentStepEvent(STEP_GENERATE, "질문 명확화 요청 중..."));
                pipelineTracer.logTrace(trace, userId, "CLARIFY");
                yield chatClarify(sessionId, message, modelId);
            }
            case WEB_SEARCH -> {
                callback.accept(new AgentStepEvent("web_search", "웹 검색 중..."));
                String searchQuery = analysis.searchQuery().isBlank() ? message : analysis.searchQuery();
                pipelineTracer.logTrace(trace, userId, "WEB_SEARCH");
                yield chatWithWebSearch(sessionId, message, searchQuery, modelId, callback);
            }
            case SEARCH -> {
                String searchQuery = analysis.searchQuery().isBlank() ? message : analysis.searchQuery();
                if (analysis.isMultiStep()) {
                    yield chatMultiStep(sessionId, searchQuery, analysis.subQueries(),
                            analysis.targetDocumentIds(), userId, trace, callback);
                } else {
                    callback.accept(new AgentStepEvent(STEP_SEARCH, "문서 검색 중..."));
                    yield chatWithRag(sessionId, message, searchQuery,
                            analysis.targetDocumentIds(), modelId, userId,
                            enableWebSearch, trace, callback);
                }
            }
        };
    }

    // ========== 레거시 파이프라인 (merge-analysis: false) ==========

    @SuppressWarnings("java:S107")
    private ChatResponse chatLegacyPipeline(String sessionId, String message, String modelId,
                                             UUID userId, boolean includePublicDocs,
                                             List<UUID> tagIds, List<UUID> collectionIds,
                                             TraceContext trace, Consumer<AgentStepEvent> callback) {
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

        return switch (decision.action()) {
            case DIRECT_ANSWER -> {
                callback.accept(new AgentStepEvent("direct", MSG_GENERATING_DIRECT));
                pipelineTracer.logTrace(trace, userId, ACTION_DIRECT_ANSWER);
                yield chatGeneral(sessionId, message, modelId);
            }
            case CLARIFY -> {
                callback.accept(new AgentStepEvent("clarify", "질문 명확화 요청 중..."));
                pipelineTracer.logTrace(trace, userId, "CLARIFY");
                yield chatClarify(sessionId, message, modelId);
            }
            case WEB_SEARCH -> {
                // 레거시 파이프라인에서는 WEB_SEARCH가 발생하지 않지만 exhaustive switch를 위해 처리
                callback.accept(new AgentStepEvent(STEP_GENERATE, MSG_GENERATING_DIRECT));
                pipelineTracer.logTrace(trace, userId, ACTION_DIRECT_ANSWER);
                yield chatGeneral(sessionId, message, modelId);
            }
            case SEARCH -> {
                callback.accept(new AgentStepEvent("decompose", "질문 복잡도 분석 중..."));
                trace.startStep("decompose");
                List<String> subQueries = multiStepReasoner.decompose(searchQuery);
                trace.endStep(Map.of("isMultiStep", !subQueries.isEmpty()));

                if (subQueries.size() > 1) {
                    yield chatMultiStep(sessionId, searchQuery, subQueries,
                            decision.targetDocumentIds(), userId, trace, callback);
                } else {
                    callback.accept(new AgentStepEvent(STEP_SEARCH, "문서 검색 중..."));
                    yield chatWithRag(sessionId, message, searchQuery,
                            decision.targetDocumentIds(), modelId, userId,
                            false, trace, callback);
                }
            }
        };
    }

    // ========== 공통 메서드 ==========

    @SuppressWarnings("java:S107")
    private ChatResponse chatWithRag(String sessionId, String message, String searchQuery,
                                      List<UUID> documentIds, String modelId, UUID userId,
                                      boolean enableWebSearch,
                                      TraceContext trace, Consumer<AgentStepEvent> callback) {
        trace.startStep(STEP_SEARCH);
        List<ChunkSearchResult> searchResults = searchService.search(searchQuery, documentIds);
        trace.endStep(Map.of("resultCount", searchResults.size()));

        // 문서 검색 결과가 빈약하고 웹검색이 활성화된 경우 웹검색 폴백
        if (enableWebSearch && searchResults.isEmpty() && tavilySearchService.isAvailable()) {
            callback.accept(new AgentStepEvent("web_search", "문서에서 정보를 찾지 못해 웹 검색 중..."));
            return chatWithWebSearch(sessionId, message, searchQuery, modelId, callback);
        }

        callback.accept(new AgentStepEvent(STEP_GENERATE, "답변 생성 중..."));

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

        trace.startStep(STEP_GENERATE);
        Flux<String> tokenStream = chatClient(modelId).prompt()
                .system(ragSystemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    trace.endStep(Map.of("responseLength", fullResponse.length()));
                    pipelineTracer.logTrace(trace, userId, "SEARCH");
                    conversationService.addMessage(sessionId,
                            ConversationMessage.assistant(fullResponse.toString(), sourceRefs));
                    evaluationService.evaluateIfSampled(message, context, fullResponse.toString());
                });

        return new ChatResponse(tokenStream, sources, sourceRefs);
    }

    private ChatResponse chatMultiStep(String sessionId, String searchQuery,
                                        List<String> subQueries, List<UUID> documentIds,
                                        UUID userId, TraceContext trace,
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
                    pipelineTracer.logTrace(trace, userId, "SEARCH");
                    conversationService.addMessage(sessionId,
                            ConversationMessage.assistant(fullResponse.toString(), sourceRefs));
                });

        return new ChatResponse(tokenStream, sources, sourceRefs);
    }

    private ChatResponse chatWithWebSearch(String sessionId, String message, String searchQuery,
                                              String modelId,
                                              Consumer<AgentStepEvent> callback) {
        List<String> webResults;
        try {
            webResults = tavilySearchService.searchStrict(searchQuery, 5);
        } catch (WebSearchException e) {
            String errorMsg = e.getMessage();
            conversationService.addMessage(sessionId,
                    ConversationMessage.assistant(errorMsg, List.of()));
            return new ChatResponse(Flux.just(errorMsg), List.of(), List.of());
        }

        callback.accept(new AgentStepEvent(STEP_GENERATE, "웹 검색 결과로 답변 생성 중..."));

        String webContext = webResults.isEmpty()
                ? "(웹 검색 결과 없음)"
                : String.join("\n\n", webResults);
        String history = buildHistory(sessionId);
        String userPrompt = """
                ---
                [이전 대화]
                %s
                ---
                [웹 검색 결과]
                %s
                ---

                질문: %s
                """.formatted(history, webContext, message);

        List<SourceInfo> sources = webResults.stream()
                .filter(r -> r.contains("(출처: "))
                .map(r -> {
                    int urlStart = r.lastIndexOf("(출처: ") + 5;
                    int urlEnd = r.lastIndexOf(")");
                    String url = (urlStart > 5 && urlEnd > urlStart) ? r.substring(urlStart, urlEnd) : "";
                    int titleEnd = r.indexOf("]");
                    String title = (titleEnd > 1) ? r.substring(1, titleEnd) : "웹 검색";
                    return new SourceInfo(url, title, 0, truncate(r, 100));
                })
                .toList();
        List<ConversationMessage.SourceRef> sourceRefs = toSourceRefs(sources);

        StringBuilder fullResponse = new StringBuilder();

        Flux<String> tokenStream = chatClient(modelId).prompt()
                .system(webSearchSystemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> conversationService.addMessage(sessionId,
                        ConversationMessage.assistant(fullResponse.toString(), sourceRefs)));

        return new ChatResponse(tokenStream, sources, sourceRefs);
    }

    private ChatResponse chatGeneral(String sessionId, String message, String modelId) {
        String history = buildHistory(sessionId);
        String userPrompt = history.equals("(없음)") ? message
                : "[이전 대화]" + "\n" + history + "\n\n" + "질문: " + message;

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
        String prompt = clarifyPrompt.formatted(message);

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
                .map(r -> "[출처: " + r.filename() + ", 청크 " + r.chunkIndex() + "]\n" + r.contextContent())
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
