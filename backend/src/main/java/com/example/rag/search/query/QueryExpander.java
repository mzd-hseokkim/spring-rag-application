package com.example.rag.search.query;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class QueryExpander {

    private static final String EXPAND_PROMPT = """
            아래 질문에 대해 같은 의미이지만 다른 표현의 검색 질의를 %d개 생성하세요.
            각 줄에 하나씩, 검색 질의만 출력하세요. 번호나 설명은 붙이지 마세요.

            질문: %s
            """;

    private final ChatClient chatClient;
    private final int expansionCount;

    public QueryExpander(ChatClient.Builder chatClientBuilder,
                         @Value("${app.search.expansion-count:3}") int expansionCount) {
        this.chatClient = chatClientBuilder.build();
        this.expansionCount = expansionCount;
    }

    public List<String> expand(String query) {
        String prompt = EXPAND_PROMPT.formatted(expansionCount, query);

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content()
                .trim();

        List<String> queries = new ArrayList<>();
        queries.add(query); // 원본 질의 포함

        Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(expansionCount)
                .forEach(queries::add);

        return queries;
    }
}
