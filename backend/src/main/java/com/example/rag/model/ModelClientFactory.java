package com.example.rag.model;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ModelClientFactory {

    private final Map<UUID, ChatClient> chatClientCache = new ConcurrentHashMap<>();
    private final Map<UUID, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();

    public ChatClient getChatClient(LlmModel model) {
        return chatClientCache.computeIfAbsent(model.getId(), id -> createChatClient(model));
    }

    public EmbeddingModel getEmbeddingModel(LlmModel model) {
        return embeddingModelCache.computeIfAbsent(model.getId(), id -> createEmbeddingModel(model));
    }

    public void evict(UUID modelId) {
        chatClientCache.remove(modelId);
        embeddingModelCache.remove(modelId);
    }

    public void evictAll() {
        chatClientCache.clear();
        embeddingModelCache.clear();
    }

    private ChatClient createChatClient(LlmModel model) {
        ChatModel chatModel = switch (model.getProvider()) {
            case OLLAMA -> createOllamaChatModel(model);
            case ANTHROPIC -> createAnthropicChatModel(model);
        };
        return ChatClient.builder(chatModel).build();
    }

    private EmbeddingModel createEmbeddingModel(LlmModel model) {
        if (model.getProvider() != ModelProvider.OLLAMA) {
            throw new RuntimeException("Embedding is only supported for OLLAMA provider");
        }
        OllamaApi api = OllamaApi.builder().baseUrl(model.getBaseUrl()).build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaEmbeddingOptions.builder()
                        .model(model.getModelId())
                        .build())
                .build();
    }

    private ChatModel createOllamaChatModel(LlmModel model) {
        OllamaApi api = OllamaApi.builder().baseUrl(model.getBaseUrl()).build();
        OllamaChatOptions.Builder options = OllamaChatOptions.builder()
                .model(model.getModelId());
        if (model.getTemperature() != null) {
            options.temperature(model.getTemperature());
        }
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options.build())
                .build();
    }

    private ChatModel createAnthropicChatModel(LlmModel model) {
        String apiKey = resolveApiKey(model.getApiKeyRef());
        AnthropicApi api = AnthropicApi.builder().apiKey(apiKey).build();
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
                .model(model.getModelId());
        if (model.getTemperature() != null) {
            options.temperature(model.getTemperature());
        }
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options.build())
                .build();
    }

    private String resolveApiKey(String apiKeyRef) {
        if (apiKeyRef == null || apiKeyRef.isBlank()) {
            throw new RuntimeException("API key reference is not set");
        }
        String key = System.getenv(apiKeyRef);
        if (key == null || key.isBlank()) {
            throw new RuntimeException("Environment variable not found: " + apiKeyRef);
        }
        return key;
    }
}
