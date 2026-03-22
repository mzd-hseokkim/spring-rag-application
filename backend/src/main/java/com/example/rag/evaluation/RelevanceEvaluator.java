package com.example.rag.evaluation;

import com.example.rag.common.PromptLoader;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class RelevanceEvaluator {

    private static final Logger log = LoggerFactory.getLogger("evaluation.relevance");

    private final String prompt;
    private final ModelClientProvider modelProvider;

    public RelevanceEvaluator(ModelClientProvider modelProvider, PromptLoader promptLoader) {
        this.modelProvider = modelProvider;
        this.prompt = promptLoader.load("eval-relevance.txt");
    }

    private ChatClient chatClient() {
        return modelProvider.getChatClient(ModelPurpose.EVALUATION);
    }

    public void evaluate(String query, String response) {
        try {
            String result = chatClient().prompt()
                    .user(prompt.formatted(query, truncate(response, 1000)))
                    .call()
                    .content();
            if (result == null) {
                log.warn("relevance evaluation returned null");
                return;
            }
            result = result.trim();

            String digits = result.replaceAll("\\D", "");
            int score = digits.isEmpty() ? 0 : Integer.parseInt(digits.substring(0, 1));
            if (log.isInfoEnabled()) {
                log.info("relevance_score={} query=\"{}\"", score, truncate(query, 50));
            }
        } catch (Exception e) {
            log.warn("Relevance evaluation failed: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
