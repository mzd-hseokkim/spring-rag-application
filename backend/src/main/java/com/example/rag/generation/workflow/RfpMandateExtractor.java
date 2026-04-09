package com.example.rag.generation.workflow;

import com.example.rag.common.PromptLoader;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * RFP 청크에서 "제안서 작성 의무 항목"과 "평가 배점표"를 추출한다.
 * RequirementExtractor와 별개로 동작하며, 추출 실패 시 빈 RfpMandates를 반환한다.
 */
@Service
public class RfpMandateExtractor {

    private static final Logger log = LoggerFactory.getLogger(RfpMandateExtractor.class);
    private static final String CHUNK_SEPARATOR = "\n---\n";
    private static final int CONTENT_CHAR_LIMIT = 50_000;
    private static final String TRUNCATION_SUFFIX = "\n... (이하 생략)";

    private final ModelClientProvider modelClientProvider;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public RfpMandateExtractor(ModelClientProvider modelClientProvider,
                                PromptLoader promptLoader,
                                ObjectMapper objectMapper) {
        this.modelClientProvider = modelClientProvider;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
    }

    /**
     * RFP 청크에서 의무 작성 항목과 배점표를 추출.
     * 추출 실패 또는 LLM 오류 시 빈 RfpMandates 반환 (후속 단계가 fallback 동작).
     */
    public RfpMandates extract(List<String> customerChunks, String userInput) {
        if (customerChunks == null || customerChunks.isEmpty()) {
            return RfpMandates.empty();
        }

        String content = String.join(CHUNK_SEPARATOR, customerChunks);
        if (content.length() > CONTENT_CHAR_LIMIT) {
            content = content.substring(0, CONTENT_CHAR_LIMIT) + TRUNCATION_SUFFIX;
        }
        final String documentContent = content;
        String input = userInput != null ? userInput : "";

        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.GENERATION);
        String prompt = promptLoader.load("generation-extract-rfp-mandates.txt");

        String response;
        try {
            response = client.prompt()
                    .user(u -> u.text(prompt)
                            .param("documentContent", documentContent)
                            .param("userInput", input))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("RFP mandate extraction LLM call failed: {}", e.getMessage());
            return RfpMandates.empty();
        }

        return parseResponse(response);
    }

    private RfpMandates parseResponse(String response) {
        if (response == null || response.isBlank()) {
            log.warn("RFP mandate extraction returned empty response");
            return RfpMandates.empty();
        }

        try {
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            int objStart = json.indexOf('{');
            int objEnd = json.lastIndexOf('}');
            if (objStart < 0 || objEnd <= objStart) {
                log.warn("RFP mandate response is not JSON object: {}",
                        response.substring(0, Math.min(response.length(), 200)));
                return RfpMandates.empty();
            }
            String jsonCandidate = json.substring(objStart, objEnd + 1);
            RfpMandates parsed = objectMapper.readValue(jsonCandidate, RfpMandates.class);

            // null 필드를 빈 컬렉션으로 정규화
            RfpMandates result = new RfpMandates(
                    parsed.mandatoryItems() != null ? parsed.mandatoryItems() : List.of(),
                    parsed.evaluationWeights() != null ? parsed.evaluationWeights() : Map.of(),
                    parsed.totalScore());

            log.info("Extracted RFP mandates: {} mandatory items, {} weight entries, totalScore={}",
                    result.mandatoryItems().size(),
                    result.evaluationWeights().size(),
                    result.totalScore());

            return result;
        } catch (Exception e) {
            log.warn("Failed to parse RFP mandate response: {} | preview: {}",
                    e.getMessage(),
                    response.substring(0, Math.min(response.length(), 200)));
            return RfpMandates.empty();
        }
    }
}
