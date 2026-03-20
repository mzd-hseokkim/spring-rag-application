package com.example.rag.conversation;

import java.time.Instant;

public record ConversationMessage(
        String role,    // "user" or "assistant"
        String content,
        Instant timestamp
) {
    public static ConversationMessage user(String content) {
        return new ConversationMessage("user", content, Instant.now());
    }

    public static ConversationMessage assistant(String content) {
        return new ConversationMessage("assistant", content, Instant.now());
    }
}
