package com.example.rag.search.query;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class QueryRouter {

    private static final String ROUTE_PROMPT = """
            아래 질문이 문서 검색이 필요한 질문인지, 일반 대화인지 분류하세요.
            "RAG" 또는 "GENERAL" 중 하나만 답하세요.

            질문: %s
            """;

    private final ChatClient chatClient;

    public QueryRouter(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public QueryRoute route(String query) {
        String response = chatClient.prompt()
                .user(ROUTE_PROMPT.formatted(query))
                .call()
                .content()
                .trim()
                .toUpperCase();

        if (response.contains("GENERAL")) {
            return QueryRoute.GENERAL;
        }
        return QueryRoute.RAG;
    }
}
