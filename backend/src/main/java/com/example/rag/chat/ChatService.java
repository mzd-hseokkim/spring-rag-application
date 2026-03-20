package com.example.rag.chat;

import com.example.rag.search.ChunkSearchResult;
import com.example.rag.search.SearchService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final String SYSTEM_PROMPT = """
            당신은 주어진 문서를 기반으로 질문에 답변하는 도우미입니다.

            아래 문서 내용을 참고하여 질문에 답변하세요.
            문서에 없는 내용은 "제공된 문서에서 해당 정보를 찾을 수 없습니다"라고 답변하세요.
            """;

    private final ChatClient chatClient;
    private final SearchService searchService;

    public ChatService(ChatClient.Builder chatClientBuilder, SearchService searchService) {
        this.chatClient = chatClientBuilder.build();
        this.searchService = searchService;
    }

    public ChatResponse chat(String message) {
        List<ChunkSearchResult> searchResults = searchService.search(message);

        String context = buildContext(searchResults);
        String userPrompt = """
                ---
                [컨텍스트]
                %s
                ---

                질문: %s
                """.formatted(context, message);

        Flux<String> tokenStream = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .stream()
                .content();

        List<SourceInfo> sources = searchResults.stream()
                .map(r -> new SourceInfo(r.documentId().toString(), r.filename(),
                        r.chunkIndex(), truncate(r.content(), 100)))
                .toList();

        return new ChatResponse(tokenStream, sources);
    }

    private String buildContext(List<ChunkSearchResult> results) {
        return results.stream()
                .map(r -> "[출처: %s, 청크 %d]\n%s".formatted(r.filename(), r.chunkIndex(), r.content()))
                .collect(Collectors.joining("\n\n"));
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    public record ChatResponse(Flux<String> tokens, List<SourceInfo> sources) {}

    public record SourceInfo(String documentId, String filename, int chunkIndex, String excerpt) {}
}
