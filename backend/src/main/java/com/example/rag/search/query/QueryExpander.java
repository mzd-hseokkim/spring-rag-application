package com.example.rag.search.query;

import com.example.rag.common.PromptLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class QueryExpander {

    private final String expandPrompt;
    private final ChatClient chatClient;
    private final int expansionCount;

    public QueryExpander(ChatClient.Builder chatClientBuilder,
                         @Value("${app.search.expansion-count:3}") int expansionCount,
                         PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.expansionCount = expansionCount;
        this.expandPrompt = promptLoader.load("expand.txt");
    }

    public List<String> expand(String query) {
        String prompt = expandPrompt.formatted(expansionCount, query);

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content()
                .trim();

        List<String> queries = new ArrayList<>();
        queries.add(query);

        Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(expansionCount)
                .forEach(queries::add);

        return queries;
    }
}
