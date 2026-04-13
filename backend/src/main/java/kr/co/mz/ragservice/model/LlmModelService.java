package kr.co.mz.ragservice.model;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class LlmModelService {

    private final LlmModelRepository repository;

    public LlmModelService(LlmModelRepository repository) {
        this.repository = repository;
    }

    public List<LlmModel> findAll() {
        return repository.findAll();
    }

    public List<LlmModel> findByPurpose(ModelPurpose purpose) {
        return repository.findByPurposeAndActiveTrue(purpose);
    }

    public LlmModel findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Model not found: " + id));
    }

    public LlmModel getDefaultModel(ModelPurpose purpose) {
        return repository.findByPurposeAndDefaultModelTrue(purpose)
                .or(() -> {
                    // GENERATION/QUESTIONNAIRE에 전용 모델이 없으면 CHAT 모델로 fallback
                    if (purpose == ModelPurpose.GENERATION || purpose == ModelPurpose.QUESTIONNAIRE) {
                        return repository.findByPurposeAndDefaultModelTrue(ModelPurpose.CHAT);
                    }
                    return java.util.Optional.empty();
                })
                .orElseThrow(() -> new RuntimeException("No default model for purpose: " + purpose));
    }

    @Transactional
    public LlmModel create(LlmModel model) {
        return repository.save(model);
    }

    @Transactional
    public LlmModel update(UUID id, LlmModel updates) {
        LlmModel model = findById(id);
        model.setProvider(updates.getProvider());
        model.setModelId(updates.getModelId());
        model.setDisplayName(updates.getDisplayName());
        model.setPurpose(updates.getPurpose());
        model.setBaseUrl(updates.getBaseUrl());
        model.setApiKeyRef(updates.getApiKeyRef());
        model.setTemperature(updates.getTemperature());
        model.setMaxTokens(updates.getMaxTokens());
        return repository.save(model);
    }

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    @Transactional
    public void activate(UUID id) {
        LlmModel model = findById(id);
        model.setActive(true);
        repository.save(model);
    }

    @Transactional
    public void deactivate(UUID id) {
        LlmModel model = findById(id);
        model.setActive(false);
        model.setDefaultModel(false);
        repository.save(model);
    }

    @Transactional
    public void setDefault(UUID id) {
        LlmModel model = findById(id);
        repository.clearDefaultByPurpose(model.getPurpose());
        model.setDefaultModel(true);
        repository.save(model);
    }
}
