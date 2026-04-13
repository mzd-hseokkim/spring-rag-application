package kr.co.mz.ragservice.evaluation;

import kr.co.mz.ragservice.common.PromptLoader;
import kr.co.mz.ragservice.model.ModelClientProvider;
import kr.co.mz.ragservice.model.ModelPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class FaithfulnessEvaluator {

    private static final Logger log = LoggerFactory.getLogger("evaluation.faithfulness");

    private final String prompt;
    private final ModelClientProvider modelProvider;

    public FaithfulnessEvaluator(ModelClientProvider modelProvider, PromptLoader promptLoader) {
        this.modelProvider = modelProvider;
        this.prompt = promptLoader.load("eval-faithfulness.txt");
    }

    private ChatClient chatClient() {
        return modelProvider.getChatClient(ModelPurpose.EVALUATION);
    }

    public void evaluate(String context, String response) {
        try {
            String raw = chatClient().prompt()
                    .user(prompt.formatted(truncate(context, 2000), truncate(response, 1000)))
                    .call()
                    .content();
            if (raw == null) {
                log.warn("faithfulness evaluation returned null");
                return;
            }
            String result = raw.trim().toUpperCase();

            boolean faithful = result.contains("FAITHFUL") && !result.contains("NOT_FAITHFUL");
            if (log.isInfoEnabled()) {
                log.info("faithfulness={} response_preview=\"{}\"",
                        faithful ? "FAITHFUL" : "NOT_FAITHFUL",
                        truncate(response, 50));
            }
        } catch (Exception e) {
            log.warn("Faithfulness evaluation failed: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
