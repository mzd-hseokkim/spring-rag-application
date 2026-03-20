package com.example.rag.search.query;

import com.example.rag.common.PromptLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class QueryRouter {

    private final String routePrompt;
    private final ChatClient chatClient;

    public QueryRouter(ChatClient.Builder chatClientBuilder, PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.routePrompt = promptLoader.load("route.txt");
    }

    public QueryRoute route(String query) {
        String response = chatClient.prompt()
                .user(routePrompt.formatted(query))
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
