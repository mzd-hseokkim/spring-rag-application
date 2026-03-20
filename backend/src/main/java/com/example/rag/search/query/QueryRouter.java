package com.example.rag.search.query;

import com.example.rag.common.PromptLoader;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class QueryRouter {

    private final String routePrompt;
    private final ModelClientProvider modelProvider;

    public QueryRouter(ModelClientProvider modelProvider, PromptLoader promptLoader) {
        this.modelProvider = modelProvider;
        this.routePrompt = promptLoader.load("route.txt");
    }

    private ChatClient chatClient() {
        return modelProvider.getChatClient(ModelPurpose.QUERY);
    }

    public QueryRoute route(String query) {
        String response = chatClient().prompt()
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
