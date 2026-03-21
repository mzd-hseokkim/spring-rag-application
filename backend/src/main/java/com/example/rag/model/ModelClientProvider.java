package com.example.rag.model;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ModelClientProvider {

    private final LlmModelService modelService;
    private final ModelClientFactory clientFactory;

    public ModelClientProvider(LlmModelService modelService, ModelClientFactory clientFactory) {
        this.modelService = modelService;
        this.clientFactory = clientFactory;
    }

    public ChatClient getChatClient(ModelPurpose purpose) {
        LlmModel model = modelService.getDefaultModel(purpose);
        return clientFactory.getChatClient(model);
    }

    public ChatClient getChatClient(UUID modelId) {
        LlmModel model = modelService.findById(modelId);
        return clientFactory.getChatClient(model);
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
    }

    public void evictAllCaches() {
        clientFactory.evictAll();
    }
}
