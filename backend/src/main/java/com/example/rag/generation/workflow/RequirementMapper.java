package com.example.rag.generation.workflow;

import com.example.rag.common.PromptLoader;
import com.example.rag.generation.dto.OutlineNode;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import com.example.rag.questionnaire.workflow.Requirement;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RequirementMapper {

    private static final Logger log = LoggerFactory.getLogger(RequirementMapper.class);
    private static final TypeReference<Map<String, List<String>>> MAPPING_TYPE = new TypeReference<>() {};

    private final ModelClientProvider modelClientProvider;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public RequirementMapper(ModelClientProvider modelClientProvider,
                              PromptLoader promptLoader,
                              ObjectMapper objectMapper) {
        this.modelClientProvider = modelClientProvider;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
    }

    /**
     * 요구사항을 목차에 매핑한다.
     * @return key=목차key, value=요구사항ID 리스트
     */
    public Map<String, List<String>> map(List<OutlineNode> outline, List<Requirement> requirements) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);
        String prompt = promptLoader.load("generation-map-requirements.txt");

        String outlineJson;
        String reqJson;
        try {
            outlineJson = objectMapper.writeValueAsString(outline);
            reqJson = objectMapper.writeValueAsString(requirements);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize for mapping", e);
        }

        String content = client.prompt()
                .user(u -> u.text(prompt)
                        .param("outline", outlineJson)
                        .param("requirements", reqJson))
                .call()
                .content();

        Map<String, List<String>> mapping = parseMapping(content);
        log.info("Requirement mapping complete: {} outline keys mapped", mapping.size());
        return mapping;
    }

    private Map<String, List<String>> parseMapping(String content) {
        if (content == null || content.isBlank()) return Map.of();
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            return objectMapper.readValue(json, MAPPING_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse requirement mapping JSON: {}", e.getMessage());
            return Map.of();
        }
    }
}
