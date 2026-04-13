package kr.co.mz.ragservice.search.query;

import kr.co.mz.ragservice.common.PromptLoader;
import kr.co.mz.ragservice.model.ModelClientProvider;
import kr.co.mz.ragservice.model.ModelPurpose;
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
        String raw = chatClient().prompt()
                .user(routePrompt.formatted(query))
                .call()
                .content();
        if (raw == null) {
            return QueryRoute.RAG;
        }
        String response = raw.trim().toUpperCase();

        if (response.contains("GENERAL")) {
            return QueryRoute.GENERAL;
        }
        return QueryRoute.RAG;
    }
}
