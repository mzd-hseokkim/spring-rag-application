package com.example.rag.search.query;

import com.example.rag.common.PromptLoader;
import com.example.rag.conversation.ConversationMessage;
import com.example.rag.conversation.ConversationService;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class QueryCompressor {

    private final String compressPrompt;
    private final ModelClientProvider modelProvider;
    private final ConversationService conversationService;

    public QueryCompressor(ModelClientProvider modelProvider,
                           ConversationService conversationService,
                           PromptLoader promptLoader) {
        this.modelProvider = modelProvider;
        this.conversationService = conversationService;
        this.compressPrompt = promptLoader.load("compress.txt");
    }

    private ChatClient chatClient() {
        return modelProvider.getChatClient(ModelPurpose.QUERY);
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

        String result = chatClient().prompt()
                .user(prompt)
                .call()
                .content();
        return result != null ? result.trim() : currentMessage;
    }
}
