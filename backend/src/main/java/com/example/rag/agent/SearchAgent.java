package com.example.rag.agent;

import com.example.rag.common.PromptLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class SearchAgent {

    private final String decidePrompt;
    private final ChatClient chatClient;

    public SearchAgent(ChatClient.Builder chatClientBuilder, PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.decidePrompt = promptLoader.load("agent-decide.txt");
    }

    public AgentAction decide(String query) {
        String response = chatClient.prompt()
                .user(decidePrompt.formatted(query))
                .call()
                .content()
                .trim()
                .toUpperCase();

        if (response.contains("DIRECT_ANSWER") || response.contains("DIRECT")) {
            return AgentAction.DIRECT_ANSWER;
        }
        if (response.contains("CLARIFY")) {
            return AgentAction.CLARIFY;
        }
        return AgentAction.SEARCH;
    }
}
