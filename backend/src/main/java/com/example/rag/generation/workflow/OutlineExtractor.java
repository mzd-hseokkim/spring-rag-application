package com.example.rag.generation.workflow;

import com.example.rag.common.PromptLoader;
import com.example.rag.generation.dto.OutlineNode;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OutlineExtractor {

    private static final Logger log = LoggerFactory.getLogger(OutlineExtractor.class);
    private static final TypeReference<List<OutlineNode>> OUTLINE_LIST_TYPE = new TypeReference<>() {};

    private final ModelClientProvider modelClientProvider;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public OutlineExtractor(ModelClientProvider modelClientProvider,
                             PromptLoader promptLoader,
                             ObjectMapper objectMapper) {
        this.modelClientProvider = modelClientProvider;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
    }

    public List<OutlineNode> extract(List<String> customerChunks, String userInput) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);
        String prompt = promptLoader.load("generation-extract-outline.txt");

        String rawContent = String.join("\n---\n", customerChunks);
        if (rawContent.length() > 50_000) {
            rawContent = rawContent.substring(0, 50_000) + "\n... (이하 생략)";
        }
        final String documentContent = rawContent;
        String input = userInput != null ? userInput : "";

        String content = client.prompt()
                .user(u -> u.text(prompt)
                        .param("documentContent", documentContent)
                        .param("userInput", input))
                .call()
                .content();

        List<OutlineNode> outline = parseOutline(content);
        log.info("Extracted outline: {} top-level sections", outline.size());
        return outline;
    }

    public String toJson(List<OutlineNode> outline) {
        try {
            return objectMapper.writeValueAsString(outline);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize outline", e);
        }
    }

    public List<OutlineNode> fromJson(String json) {
        try {
            return objectMapper.readValue(json, OUTLINE_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse outline JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private List<OutlineNode> parseOutline(String content) {
        if (content == null || content.isBlank()) return List.of();
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            return objectMapper.readValue(json, OUTLINE_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse outline JSON: {}", e.getMessage());
            return List.of();
        }
    }
}
