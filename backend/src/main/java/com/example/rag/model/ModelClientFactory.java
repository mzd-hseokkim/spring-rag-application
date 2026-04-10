package com.example.rag.model;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.AzureKeyCredential;
import org.springframework.ai.azure.openai.AzureOpenAiEmbeddingModel;
import org.springframework.ai.azure.openai.AzureOpenAiEmbeddingOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.MetadataMode;
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
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
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

    public ChatModel getChatModel(LlmModel model) {
        return switch (model.getProvider()) {
            case OLLAMA -> createOllamaChatModel(model);
            case ANTHROPIC -> createAnthropicChatModel(model);
            case AZURE_OPENAI -> throw new com.example.rag.common.RagException(
                    "Chat is not supported for AZURE_OPENAI provider");
        };
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
            case AZURE_OPENAI -> throw new com.example.rag.common.RagException(
                    "Chat is not supported for AZURE_OPENAI provider");
        };
        return ChatClient.builder(chatModel).build();
    }

    private EmbeddingModel createEmbeddingModel(LlmModel model) {
        return switch (model.getProvider()) {
            case OLLAMA -> createOllamaEmbeddingModel(model);
            case AZURE_OPENAI -> createAzureOpenAiEmbeddingModel(model);
            default -> throw new com.example.rag.common.RagException(
                    "Embedding is not supported for " + model.getProvider() + " provider");
        };
    }

    private EmbeddingModel createOllamaEmbeddingModel(LlmModel model) {
        OllamaApi api = OllamaApi.builder().baseUrl(model.getBaseUrl()).build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaEmbeddingOptions.builder()
                        .model(model.getModelId())
                        .build())
                .build();
    }

    private EmbeddingModel createAzureOpenAiEmbeddingModel(LlmModel model) {
        String apiKey = resolveApiKey(model.getApiKeyRef());
        var openAIClient = new OpenAIClientBuilder()
                .endpoint(model.getBaseUrl())
                .credential(new AzureKeyCredential(apiKey))
                .buildClient();
        var options = AzureOpenAiEmbeddingOptions.builder()
                .deploymentName(model.getModelId())
                .build();
        return new AzureOpenAiEmbeddingModel(openAIClient, MetadataMode.EMBED, options);
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
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMinutes(10));
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(new ReactorClientHttpRequestFactory(httpClient));
        AnthropicApi api = AnthropicApi.builder()
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();
        int maxTokens = model.getMaxTokens() != null ? model.getMaxTokens() : 10240;
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
                .model(model.getModelId())
                .maxTokens(maxTokens);
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
            throw new com.example.rag.common.RagException("API key is not set");
        }
        // 환경변수명 패턴인 경우 (대문자, 숫자, 밑줄) 환경변수에서 조회
        if (apiKeyRef.matches("^[A-Z][A-Z0-9_]*$")) {
            String key = System.getenv(apiKeyRef);
            if (key == null || key.isBlank()) {
                throw new com.example.rag.common.RagException("Environment variable not found: " + apiKeyRef);
            }
            return key;
        }
        // 그 외에는 API 키 값이 직접 입력된 경우로 간주
        return apiKeyRef;
    }
}
