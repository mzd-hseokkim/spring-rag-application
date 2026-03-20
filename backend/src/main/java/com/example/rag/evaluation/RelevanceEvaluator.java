package com.example.rag.evaluation;

import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class RelevanceEvaluator {

    private static final Logger log = LoggerFactory.getLogger("evaluation.relevance");

    private static final String PROMPT = """
            아래 질문과 답변이 주어졌을 때, 답변이 질문에 적절한지 1~5 점수로 평가하세요.
            숫자만 답하세요.

            [질문]
            %s

            [답변]
            %s
            """;

    private final ModelClientProvider modelProvider;

    public RelevanceEvaluator(ModelClientProvider modelProvider) {
        this.modelProvider = modelProvider;
    }

    private ChatClient chatClient() {
        return modelProvider.getChatClient(ModelPurpose.EVALUATION);
    }

    public void evaluate(String query, String response) {
        try {
            String result = chatClient().prompt()
                    .user(PROMPT.formatted(query, truncate(response, 1000)))
                    .call()
                    .content()
                    .trim();

            String digits = result.replaceAll("[^0-9]", "");
            int score = digits.isEmpty() ? 0 : Integer.parseInt(digits.substring(0, 1));
            log.info("relevance_score={} query=\"{}\"", score, truncate(query, 50));
        } catch (Exception e) {
            log.warn("Relevance evaluation failed: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
