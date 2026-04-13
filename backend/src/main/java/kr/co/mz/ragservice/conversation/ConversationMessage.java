package kr.co.mz.ragservice.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationMessage(
        String role,    // "user" or "assistant"
        String content,
        Instant timestamp,
        List<SourceRef> sources
) {
    public static ConversationMessage user(String content) {
        return new ConversationMessage("user", content, Instant.now(), null);
    }

    public static ConversationMessage assistant(String content) {
        return new ConversationMessage("assistant", content, Instant.now(), null);
    }

    public static ConversationMessage assistant(String content, List<SourceRef> sources) {
        return new ConversationMessage("assistant", content, Instant.now(), sources);
    }

    public record SourceRef(
            String documentId,
            String filename,
            int chunkIndex,
            String excerpt
    ) {}
}
