package kr.co.mz.ragservice.model.adapter;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AnthropicAdapter {

    public OllamaAdapter.TestResult test(String apiKeyRef, String modelId) {
        long start = System.currentTimeMillis();
        try {
            String apiKey = resolveApiKey(apiKeyRef);
            if (apiKey == null || apiKey.isBlank()) {
                return new OllamaAdapter.TestResult(false, 0, "API key is not set");
            }

            RestClient client = RestClient.create("https://api.anthropic.com");
            String body = """
                    {"model":"%s","max_tokens":10,"messages":[{"role":"user","content":"ping"}]}
                    """.formatted(modelId);

            client.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            long latency = System.currentTimeMillis() - start;
            return new OllamaAdapter.TestResult(true, latency, "OK");
        } catch (Exception e) {
            return new OllamaAdapter.TestResult(false, System.currentTimeMillis() - start, e.getMessage());
        }
    }

    private String resolveApiKey(String apiKeyRef) {
        if (apiKeyRef == null || apiKeyRef.isBlank()) return null;
        if (apiKeyRef.startsWith("sk-")) return apiKeyRef;
        return System.getenv(apiKeyRef);
    }
}
