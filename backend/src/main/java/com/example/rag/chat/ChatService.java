package com.example.rag.chat;

import com.example.rag.conversation.ConversationMessage;
import com.example.rag.conversation.ConversationService;
import com.example.rag.search.ChunkSearchResult;
import com.example.rag.search.SearchService;
import com.example.rag.search.query.QueryCompressor;
import com.example.rag.search.query.QueryRoute;
import com.example.rag.search.query.QueryRouter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final String RAG_SYSTEM_PROMPT = """
            당신은 주어진 문서를 기반으로 질문에 답변하는 도우미입니다.

            아래 문서 내용을 참고하여 질문에 답변하세요.
            문서에 없는 내용은 "제공된 문서에서 해당 정보를 찾을 수 없습니다"라고 답변하세요.
            """;

    private static final String GENERAL_SYSTEM_PROMPT = """
            당신은 친절한 대화 도우미입니다. 사용자와 자연스럽게 대화하세요.
            """;

    private final ChatClient chatClient;
    private final SearchService searchService;
    private final ConversationService conversationService;
    private final QueryCompressor queryCompressor;
    private final QueryRouter queryRouter;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       SearchService searchService,
                       ConversationService conversationService,
                       QueryCompressor queryCompressor,
                       QueryRouter queryRouter) {
        this.chatClient = chatClientBuilder.build();
        this.searchService = searchService;
        this.conversationService = conversationService;
        this.queryCompressor = queryCompressor;
        this.queryRouter = queryRouter;
    }

    public ChatResponse chat(String sessionId, String message) {
        // 대화 이력에 사용자 메시지 저장
        conversationService.addMessage(sessionId, ConversationMessage.user(message));

        // 대화 압축: 멀티턴 대화를 독립 질의로 변환
        String searchQuery = queryCompressor.compress(sessionId, message);

        // 쿼리 라우팅
        QueryRoute route = queryRouter.route(searchQuery);

        if (route == QueryRoute.GENERAL) {
            return chatGeneral(sessionId, message);
        }
        return chatWithRag(sessionId, message, searchQuery);
    }

    private ChatResponse chatWithRag(String sessionId, String message, String searchQuery) {
        List<ChunkSearchResult> searchResults = searchService.search(searchQuery);

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

        Flux<String> tokenStream = chatClient.prompt()
                .system(RAG_SYSTEM_PROMPT)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> conversationService.addMessage(sessionId,
                        ConversationMessage.assistant(fullResponse.toString())));

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
                .system(GENERAL_SYSTEM_PROMPT)
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

        // 현재 메시지(마지막)는 제외 — 이미 질문에 포함됨
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
