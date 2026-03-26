package com.example.rag.questionnaire.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TavilySearchService {

    private static final Logger log = LoggerFactory.getLogger(TavilySearchService.class);
    private static final String TAVILY_API_URL = "https://api.tavily.com/search";
    private static final String ANSWER_FIELD = "answer";
    private static final String RESULTS_FIELD = "results";

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TavilySearchService(@Value("${app.tavily.api-key:}") String apiKey,
                                ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 웹 검색을 수행하되 API 에러 시 WebSearchException을 던진다.
     * 채팅 파이프라인처럼 사용자에게 에러를 전달해야 하는 경우 사용한다.
     */
    public List<String> searchStrict(String query, int maxResults) {
        if (!isAvailable()) {
            throw new WebSearchException(0);
        }
        try {
            return doSearch(query, maxResults, true);
        } catch (WebSearchException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebSearchException(0);
        } catch (Exception e) {
            throw new WebSearchException(500);
        }
    }

    /**
     * Tavily API로 웹 검색을 수행하고 결과를 텍스트 목록으로 반환한다.
     * 에러 시 빈 리스트를 반환한다.
     */
    public List<String> search(String query, int maxResults) {
        if (!isAvailable()) {
            log.warn("Tavily API key not configured, skipping web search");
            return List.of();
        }
        try {
            return doSearch(query, maxResults, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Tavily web search interrupted for query '{}'", query);
            return List.of();
        } catch (Exception e) {
            log.warn("Tavily web search failed for query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private List<String> doSearch(String query, int maxResults, boolean strict) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "query", query,
                "max_results", maxResults,
                "include_answer", true,
                "search_depth", "advanced"
        );

        String requestBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TAVILY_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + resolveApiKey())
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            if (log.isWarnEnabled()) {
                log.warn("Tavily API returned status {}: {}", response.statusCode(), response.body());
            }
            if (strict) {
                throw new WebSearchException(response.statusCode());
            }
            return List.of();
        }

        return parseResults(response.body());
    }

    private List<String> parseResults(String responseBody) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = objectMapper.readTree(responseBody);
        List<String> results = new ArrayList<>();

        parseAnswerSummary(root, results);
        parseSearchResults(root, results);

        return results;
    }

    private void parseAnswerSummary(JsonNode root, List<String> results) {
        if (root.has(ANSWER_FIELD) && !root.get(ANSWER_FIELD).isNull()) {
            String answer = root.get(ANSWER_FIELD).asText("");
            if (!answer.isBlank()) {
                results.add("[웹 검색 요약] " + answer);
            }
        }
    }

    private void parseSearchResults(JsonNode root, List<String> results) {
        if (!root.has(RESULTS_FIELD) || !root.get(RESULTS_FIELD).isArray()) {
            return;
        }
        for (JsonNode result : root.get(RESULTS_FIELD)) {
            String title = result.has("title") ? result.get("title").asText("") : "";
            String content = result.has("content") ? result.get("content").asText("") : "";
            String url = result.has("url") ? result.get("url").asText("") : "";
            if (!content.isBlank()) {
                results.add("[" + title + "] " + content + " (출처: " + url + ")");
            }
        }
    }

    private String resolveApiKey() {
        // 환경변수명이면 환경변수에서 조회
        if (!apiKey.startsWith("tvly-")) {
            String envKey = System.getenv(apiKey);
            if (envKey != null && !envKey.isBlank()) {
                return envKey;
            }
        }
        return apiKey;
    }
}
