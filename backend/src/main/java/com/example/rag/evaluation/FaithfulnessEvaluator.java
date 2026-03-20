package com.example.rag.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class FaithfulnessEvaluator {

    private static final Logger log = LoggerFactory.getLogger("evaluation.faithfulness");

    private static final String PROMPT = """
            아래 컨텍스트와 답변이 주어졌을 때, 답변이 컨텍스트에 근거하는지 평가하세요.
            "FAITHFUL" 또는 "NOT_FAITHFUL" 중 하나만 답하세요.

            [컨텍스트]
            %s

            [답변]
            %s
            """;

    private final ChatClient chatClient;

    public FaithfulnessEvaluator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public void evaluate(String context, String response) {
        try {
            String result = chatClient.prompt()
                    .user(PROMPT.formatted(truncate(context, 2000), truncate(response, 1000)))
                    .call()
                    .content()
                    .trim()
                    .toUpperCase();

            boolean faithful = result.contains("FAITHFUL") && !result.contains("NOT_FAITHFUL");
            log.info("faithfulness={} response_preview=\"{}\"",
                    faithful ? "FAITHFUL" : "NOT_FAITHFUL",
                    truncate(response, 50));
        } catch (Exception e) {
            log.warn("Faithfulness evaluation failed: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
