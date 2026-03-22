package com.example.rag.search.query;

import com.example.rag.common.PromptLoader;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class QueryExpander {

    private final String expandPrompt;
    private final ModelClientProvider modelProvider;
    private final int expansionCount;

    public QueryExpander(ModelClientProvider modelProvider,
                         @Value("${app.search.expansion-count:3}") int expansionCount,
                         PromptLoader promptLoader) {
        this.modelProvider = modelProvider;
        this.expansionCount = expansionCount;
        this.expandPrompt = promptLoader.load("expand.txt");
    }

    private ChatClient chatClient() {
        return modelProvider.getChatClient(ModelPurpose.QUERY);
    }

    public List<String> expand(String query) {
        String prompt = expandPrompt.formatted(expansionCount, query);

        String response = chatClient().prompt()
                .user(prompt)
                .call()
                .content();
        if (response == null) {
            return List.of(query);
        }
        response = response.trim();

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
