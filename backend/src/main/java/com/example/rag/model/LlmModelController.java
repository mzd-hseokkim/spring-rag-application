package com.example.rag.model;

import com.example.rag.model.adapter.AnthropicAdapter;
import com.example.rag.model.adapter.OllamaAdapter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/models")
public class LlmModelController {

    private final LlmModelService modelService;
    private final OllamaAdapter ollamaAdapter;
    private final AnthropicAdapter anthropicAdapter;
    private final ModelClientFactory clientFactory;

    public LlmModelController(LlmModelService modelService,
                               OllamaAdapter ollamaAdapter,
                               AnthropicAdapter anthropicAdapter,
                               ModelClientFactory clientFactory) {
        this.modelService = modelService;
        this.ollamaAdapter = ollamaAdapter;
        this.anthropicAdapter = anthropicAdapter;
        this.clientFactory = clientFactory;
    }

    @GetMapping
    public List<LlmModel> findAll(@RequestParam(required = false) ModelPurpose purpose) {
        if (purpose != null) {
            return modelService.findByPurpose(purpose);
        }
        return modelService.findAll();
    }

    @GetMapping("/{id}")
    public LlmModel findById(@PathVariable UUID id) {
        return modelService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LlmModel create(@RequestBody LlmModel model) {
        return modelService.create(model);
    }

    @PutMapping("/{id}")
    public LlmModel update(@PathVariable UUID id, @RequestBody LlmModel model) {
        return modelService.update(id, model);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        modelService.delete(id);
    }

    @PatchMapping("/{id}/activate")
    public void activate(@PathVariable UUID id) {
        modelService.activate(id);
    }

    @PatchMapping("/{id}/deactivate")
    public void deactivate(@PathVariable UUID id) {
        modelService.deactivate(id);
    }

    @PatchMapping("/{id}/set-default")
    public void setDefault(@PathVariable UUID id) {
        modelService.setDefault(id);
        clientFactory.evictAll();
    }

    @PostMapping("/{id}/test")
    public OllamaAdapter.TestResult testModel(@PathVariable UUID id) {
        LlmModel model = modelService.findById(id);
        return switch (model.getProvider()) {
            case OLLAMA -> ollamaAdapter.test(model.getBaseUrl(), model.getModelId());
            case ANTHROPIC -> anthropicAdapter.test(model.getApiKeyRef(), model.getModelId());
            case AZURE_OPENAI -> new OllamaAdapter.TestResult(false, 0,
                    "Azure OpenAI 모델 테스트는 아직 지원되지 않습니다.");
        };
    }

    @GetMapping("/discover/ollama")
    public List<OllamaAdapter.DiscoveredModel> discoverOllama(
            @RequestParam(required = false) String baseUrl) {
        return ollamaAdapter.discover(baseUrl);
    }
}
