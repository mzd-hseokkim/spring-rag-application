package com.example.rag.model;

import com.example.rag.dashboard.TokenUsageEntity;
import com.example.rag.dashboard.TokenUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * ChatModel 데코레이터 — LLM 호출 후 토큰 사용량을 자동 기록한다.
 * userId는 호출 시점에 SecurityContext에서 추출한다.
 */
public class TokenRecordingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(TokenRecordingChatModel.class);

    private final ChatModel delegate;
    private final String modelName;
    private final String purpose;
    private final TokenUsageRepository tokenUsageRepository;

    public TokenRecordingChatModel(ChatModel delegate, String modelName, String purpose,
                                    TokenUsageRepository tokenUsageRepository) {
        this.delegate = delegate;
        this.modelName = modelName;
        this.purpose = purpose;
        this.tokenUsageRepository = tokenUsageRepository;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        recordUsage(response);
        return response;
    }

    private void recordUsage(ChatResponse response) {
        try {
            if (response == null || response.getMetadata() == null) return;
            var usage = response.getMetadata().getUsage();
            if (usage == null) return;

            int inputTokens = usage.getPromptTokens();
            int outputTokens = usage.getCompletionTokens();
            if (inputTokens == 0 && outputTokens == 0) return;

            java.util.UUID userId = resolveCurrentUserId();
            if (userId == null) {
                log.debug("Skipping token recording: no user context (model={}, purpose={})", modelName, purpose);
                return;
            }
            tokenUsageRepository.save(new TokenUsageEntity(
                    userId, modelName, purpose, inputTokens, outputTokens, null));

            if (log.isDebugEnabled()) {
                log.debug("Token recorded: model={}, purpose={}, input={}, output={}",
                        modelName, purpose, inputTokens, outputTokens);
            }
        } catch (Exception e) {
            log.warn("Failed to record token usage: {}", e.getMessage());
        }
    }

    private java.util.UUID resolveCurrentUserId() {
        // 1. ThreadLocal (워크플로우에서 설정)
        java.util.UUID userId = TokenRecordingContext.getUserId();
        if (userId != null) return userId;

        // 2. SecurityContext (동기 호출)
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth != null && auth.getName() != null) {
                return java.util.UUID.fromString(auth.getName());
            }
        } catch (Exception e) {
            // SecurityContext가 없는 경우
        }

        // 3. 사용자 식별 불가 — 호출자가 recordUsage에서 skip 처리
        return null;
    }
}
