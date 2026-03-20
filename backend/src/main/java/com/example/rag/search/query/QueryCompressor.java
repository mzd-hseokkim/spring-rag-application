package com.example.rag.search.query;

import com.example.rag.conversation.ConversationMessage;
import com.example.rag.conversation.ConversationService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class QueryCompressor {

    private static final String COMPRESS_PROMPT = """
            아래 대화 이력과 후속 질문이 주어졌을 때, 대화 맥락을 반영하여
            독립적으로 이해 가능한 검색 질의를 한 문장으로 작성하세요.
            검색 질의만 출력하세요. 다른 설명은 하지 마세요.

            [대화 이력]
            %s

            [후속 질문]
            %s

            [독립 질의]
            """;

    private final ChatClient chatClient;
    private final ConversationService conversationService;

    public QueryCompressor(ChatClient.Builder chatClientBuilder,
                           ConversationService conversationService) {
        this.chatClient = chatClientBuilder.build();
        this.conversationService = conversationService;
    }

    public String compress(String sessionId, String currentMessage) {
        List<ConversationMessage> history = conversationService.getHistory(sessionId);

        // 현재 메시지(마지막) 제외, 이전 대화만
        List<ConversationMessage> previous = history.subList(0, Math.max(0, history.size() - 1));
        if (previous.isEmpty()) {
            return currentMessage;
        }

        String historyText = previous.stream()
                .map(m -> "%s: %s".formatted(m.role(), m.content()))
                .collect(Collectors.joining("\n"));

        String prompt = COMPRESS_PROMPT.formatted(historyText, currentMessage);

        return chatClient.prompt()
                .user(prompt)
                .call()
                .content()
                .trim();
    }
}
