package com.example.rag.search.query;

import com.example.rag.common.PromptLoader;
import com.example.rag.conversation.ConversationMessage;
import com.example.rag.conversation.ConversationService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class QueryCompressor {

    private final String compressPrompt;
    private final ChatClient chatClient;
    private final ConversationService conversationService;

    public QueryCompressor(ChatClient.Builder chatClientBuilder,
                           ConversationService conversationService,
                           PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder.build();
        this.conversationService = conversationService;
        this.compressPrompt = promptLoader.load("compress.txt");
    }

    public String compress(String sessionId, String currentMessage) {
        List<ConversationMessage> history = conversationService.getHistory(sessionId);

        List<ConversationMessage> previous = history.subList(0, Math.max(0, history.size() - 1));
        if (previous.isEmpty()) {
            return currentMessage;
        }

        String historyText = previous.stream()
                .map(m -> "%s: %s".formatted(m.role(), m.content()))
                .collect(Collectors.joining("\n"));

        String prompt = compressPrompt.formatted(historyText, currentMessage);

        return chatClient.prompt()
                .user(prompt)
                .call()
                .content()
                .trim();
    }
}
