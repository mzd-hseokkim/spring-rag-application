package com.example.rag.questionnaire.workflow;

import com.example.rag.common.PromptLoader;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import com.example.rag.questionnaire.persona.Persona;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class QuestionnaireGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(QuestionnaireGeneratorService.class);

    /** 배치당 최대 문자 수 (약 25,000토큰) */
    private static final int BATCH_CHAR_LIMIT = 50_000;
    private static final String CHUNK_SEPARATOR = "\n---\n";
    private static final String USER_INPUT_PARAM = "userInput";

    private static final String QNA_FORMAT =
            "[{\"question\":\"질문 내용\",\"answer\":\"- 핵심 포인트 1\\n- 핵심 포인트 2\",\"difficulty\":\"상|중|하\",\"category\":\"카테고리명\",\"sources\":[\"고객문서: 요구항목\",\"제안문서: 섹션명\",\"참조문서: 문서명\",\"웹: 출처\"]}]";

    private final ModelClientProvider modelClientProvider;
    private final PromptLoader promptLoader;
    private final QuestionnaireResponseParser parser;

    public QuestionnaireGeneratorService(ModelClientProvider modelClientProvider,
                                         PromptLoader promptLoader,
                                         QuestionnaireResponseParser parser) {
        this.modelClientProvider = modelClientProvider;
        this.promptLoader = promptLoader;
        this.parser = parser;
    }

    /**
     * Step 1: Map-Reduce 방식으로 문서를 분석한다.
     * - Map: 청크를 배치로 나누어 각 배치별 부분 분석
     * - Reduce: 부분 분석들을 하나로 통합
     */
    /**
     * 진행 상황 콜백 인터페이스
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String message);
    }

    public String analyzeDocuments(List<String> customerChunks, List<String> proposalChunks,
                                    String userInput, ProgressCallback callback) {
        // 유형 태그를 붙여서 하나의 청크 리스트로 합침
        List<String> taggedChunks = new ArrayList<>();
        for (String chunk : customerChunks) {
            taggedChunks.add("[고객문서]\n" + chunk);
        }
        for (String chunk : proposalChunks) {
            taggedChunks.add("[제안문서]\n" + chunk);
        }

        String customerContent = String.join(CHUNK_SEPARATOR, customerChunks);
        String proposalContent = String.join(CHUNK_SEPARATOR, proposalChunks);
        int totalLength = customerContent.length() + proposalContent.length();

        // 단일 배치로 처리 가능한 경우
        if (totalLength <= BATCH_CHAR_LIMIT) {
            return analyzeFullDocument(customerContent, proposalContent, userInput);
        }

        // 배치 분할: 태그된 청크를 배치로 나눠서 Map-Reduce
        List<List<String>> batches = splitIntoBatches(taggedChunks);
        log.info("Document analysis: {} tagged chunks split into {} batches", taggedChunks.size(), batches.size());

        List<String> partialAnalyses = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            callback.onProgress("문서 분석 중... (" + (i + 1) + "/" + batches.size() + " 배치)");
            String partial = analyzePartialDocument(batches.get(i), "고객문서+제안문서", i + 1, batches.size());
            partialAnalyses.add(partial);
            log.info("Batch {}/{} analysis complete ({} chars)", i + 1, batches.size(), partial.length());
        }

        callback.onProgress("부분 분석 결과를 통합하고 있습니다...");
        String merged = mergeAnalyses(partialAnalyses, userInput);
        log.info("Document analysis merged: {} partial analyses → {} chars", partialAnalyses.size(), merged.length());
        return merged;
    }

    /**
     * Step 2: 문서 분석 결과 + 페르소나 관점으로 질문을 생성한다.
     */
    public PersonaQna generateForPersona(Persona persona, String documentAnalysis,
                                          List<String> refContext, List<String> webContext,
                                          String userInput, int questionCount,
                                          boolean proposalProvided) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.QUESTIONNAIRE);

        String systemPrompt;
        if (persona.getPrompt() != null && !persona.getPrompt().isBlank()) {
            systemPrompt = persona.getPrompt();
        } else {
            systemPrompt = promptLoader.load("questionnaire-system.txt");
        }

        String userPrompt = promptLoader.load("questionnaire-generate.txt");
        String refText = refContext.isEmpty() ? "없음" : String.join(CHUNK_SEPARATOR, refContext);
        String webContextText = webContext.isEmpty() ? "없음" : String.join(CHUNK_SEPARATOR, webContext);
        String focusAreas = persona.getFocusAreas() != null ? persona.getFocusAreas() : "";
        String input = userInput != null ? userInput : "";

        String content = client.prompt()
                .system(systemPrompt)
                .user(u -> u.text(userPrompt)
                        .param("personaName", persona.getName())
                        .param("personaRole", persona.getRole())
                        .param("focusAreas", focusAreas)
                        .param("documentAnalysis", documentAnalysis)
                        .param("referenceContext", refText)
                        .param("webContext", webContextText)
                        .param(USER_INPUT_PARAM, input)
                        .param("questionCount", String.valueOf(questionCount))
                        .param("proposalProvided", String.valueOf(proposalProvided))
                        .param("format", QNA_FORMAT))
                .call()
                .content();

        if (log.isDebugEnabled()) {
            log.debug("Persona '{}' raw response length: {}", persona.getName(), content != null ? content.length() : 0);
        }

        List<QuestionAnswer> questions = parser.parseQuestions(content);
        return new PersonaQna(persona.getName(), persona.getRole(), questions);
    }

    // ── Map: 배치별 부분 분석 ──

    private String analyzePartialDocument(List<String> batch, String documentType, int batchIndex, int totalBatches) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.QUESTIONNAIRE);
        String userPrompt = promptLoader.load("questionnaire-analyze-partial.txt");
        String documentContent = String.join(CHUNK_SEPARATOR, batch);

        String content = client.prompt()
                .user(u -> u.text(userPrompt)
                        .param("documentContent", documentContent)
                        .param("documentType", documentType)
                        .param("batchIndex", String.valueOf(batchIndex))
                        .param("totalBatches", String.valueOf(totalBatches)))
                .call()
                .content();

        return content != null ? content : "";
    }

    // ── Reduce: 통합 분석 ──

    private String mergeAnalyses(List<String> partialAnalyses, String userInput) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.QUESTIONNAIRE);
        String userPrompt = promptLoader.load("questionnaire-analyze-merge.txt");
        String joined = String.join("\n\n---\n\n", partialAnalyses);
        String input = userInput != null ? userInput : "";

        String content = client.prompt()
                .user(u -> u.text(userPrompt)
                        .param("partialAnalyses", joined)
                        .param(USER_INPUT_PARAM, input))
                .call()
                .content();

        return content != null ? content : "";
    }

    // ── 배치 1개 (소량 문서)일 때 직접 분석 ──

    private String analyzeFullDocument(String customerContent, String proposalContent, String userInput) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.QUESTIONNAIRE);
        String userPrompt = promptLoader.load("questionnaire-analyze.txt");
        String input = userInput != null ? userInput : "";

        String content = client.prompt()
                .user(u -> u.text(userPrompt)
                        .param("customerContent", customerContent)
                        .param("proposalContent", proposalContent)
                        .param(USER_INPUT_PARAM, input))
                .call()
                .content();

        log.info("Document analysis (single batch) complete: {} chars", content != null ? content.length() : 0);
        return content != null ? content : "";
    }

    // ── 청크를 BATCH_CHAR_LIMIT 기준으로 배치 분할 ──

    private List<List<String>> splitIntoBatches(List<String> chunks) {
        List<List<String>> batches = new ArrayList<>();
        List<String> currentBatch = new ArrayList<>();
        int currentSize = 0;

        for (String chunk : chunks) {
            if (currentSize + chunk.length() > BATCH_CHAR_LIMIT && !currentBatch.isEmpty()) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentSize = 0;
            }
            currentBatch.add(chunk);
            currentSize += chunk.length();
        }

        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        return batches;
    }
}
