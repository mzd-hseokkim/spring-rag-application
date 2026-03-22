package com.example.rag.model.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Component
public class OllamaAdapter {

    private final String defaultBaseUrl;

    public OllamaAdapter(@Value("${app.model.ollama.default-base-url:http://localhost:11434}") String defaultBaseUrl) {
        this.defaultBaseUrl = defaultBaseUrl;
    }

    public List<DiscoveredModel> discover(String baseUrl) {
        String url = baseUrl != null ? baseUrl : defaultBaseUrl;
        try {
            RestClient client = RestClient.create(url);
            JsonNode response = client.get().uri("/api/tags").retrieve().body(JsonNode.class);

            List<DiscoveredModel> models = new ArrayList<>();
            if (response != null && response.has("models")) {
                for (JsonNode model : response.get("models")) {
                    models.add(new DiscoveredModel(
                            model.get("name").asText(),
                            model.has("size") ? model.get("size").asLong() : 0,
                            model.has("modified_at") ? model.get("modified_at").asText() : ""
                    ));
                }
            }
            return models;
        } catch (Exception e) {
            throw new com.example.rag.common.RagException("Failed to discover Ollama models at " + url + ": " + e.getMessage());
        }
    }

    public TestResult test(String baseUrl, String modelId) {
        String url = baseUrl != null ? baseUrl : defaultBaseUrl;
        long start = System.currentTimeMillis();
        try {
            List<DiscoveredModel> models = discover(url);
            boolean found = models.stream().anyMatch(m -> m.modelId().equals(modelId));
            long latency = System.currentTimeMillis() - start;
            if (found) {
                return new TestResult(true, latency, "OK");
            } else {
                return new TestResult(false, latency, "Model not found: " + modelId);
            }
        } catch (Exception e) {
            return new TestResult(false, System.currentTimeMillis() - start, e.getMessage());
        }
    }

    public record DiscoveredModel(String modelId, long size, String modifiedAt) {}
    public record TestResult(boolean success, long latencyMs, String message) {}
}
