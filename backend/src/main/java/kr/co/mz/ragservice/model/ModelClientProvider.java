package kr.co.mz.ragservice.model;

import kr.co.mz.ragservice.dashboard.TokenUsageRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ModelClientProvider {

    private final LlmModelService modelService;
    private final ModelClientFactory clientFactory;
    private final TokenUsageRepository tokenUsageRepository;
    private final Map<String, ChatClient> recordingClientCache = new ConcurrentHashMap<>();

    public ModelClientProvider(LlmModelService modelService, ModelClientFactory clientFactory,
                               TokenUsageRepository tokenUsageRepository) {
        this.modelService = modelService;
        this.clientFactory = clientFactory;
        this.tokenUsageRepository = tokenUsageRepository;
    }

    public ChatClient getChatClient(ModelPurpose purpose) {
        LlmModel model = modelService.getDefaultModel(purpose);
        if (purpose == ModelPurpose.GENERATION || purpose == ModelPurpose.QUESTIONNAIRE) {
            return getRecordingClient(model, purpose.name());
        }
        return clientFactory.getChatClient(model);
    }

    public ChatClient getChatClient(UUID modelId) {
        LlmModel model = modelService.findById(modelId);
        return clientFactory.getChatClient(model);
    }

    private ChatClient getRecordingClient(LlmModel model, String purpose) {
        String cacheKey = model.getId() + ":" + purpose;
        return recordingClientCache.computeIfAbsent(cacheKey, k -> {
            ChatModel baseModel = clientFactory.getChatModel(model);
            ChatModel recording = new TokenRecordingChatModel(baseModel, model.getDisplayName(), purpose, tokenUsageRepository);
            return ChatClient.builder(recording).build();
        });
    }

    public EmbeddingModel getEmbeddingModel() {
        LlmModel model = modelService.getDefaultModel(ModelPurpose.EMBEDDING);
        return clientFactory.getEmbeddingModel(model);
    }

    public String getModelName(UUID modelId) {
        return modelService.findById(modelId).getDisplayName();
    }

    public String getDefaultModelName(ModelPurpose purpose) {
        return modelService.getDefaultModel(purpose).getDisplayName();
    }

    public void evictCache(UUID modelId) {
        clientFactory.evict(modelId);
        recordingClientCache.keySet().removeIf(k -> k.startsWith(modelId.toString()));
    }

    public void evictAllCaches() {
        clientFactory.evictAll();
        recordingClientCache.clear();
    }
}
