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

    /** 배치당 최대 문자 수 (약 5,000토큰) */
    private static final int BATCH_CHAR_LIMIT = 10_000;

    private static final String QNA_FORMAT =
            "[{\"question\":\"질문 내용\",\"answer\":\"- 핵심 포인트 1\\n- 핵심 포인트 2\",\"difficulty\":\"상|중|하\",\"category\":\"카테고리명\",\"sources\":[\"과제문서: 섹션명\",\"참조문서: 문서명\",\"웹: 출처\"]}]";

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

    public String analyzeDocuments(List<String> allDocChunks, String userInput, ProgressCallback callback) {
        List<List<String>> batches = splitIntoBatches(allDocChunks);

        if (batches.size() == 1) {
            return analyzeFullDocument(batches.get(0), userInput);
        }

        log.info("Document analysis: {} chunks split into {} batches", allDocChunks.size(), batches.size());
        List<String> partialAnalyses = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            callback.onProgress("문서 분석 중... (" + (i + 1) + "/" + batches.size() + " 배치)");
            String partial = analyzePartialDocument(batches.get(i), i + 1, batches.size());
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
                                          String userInput, int questionCount) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);

        String systemPrompt;
        if (persona.getPrompt() != null && !persona.getPrompt().isBlank()) {
            systemPrompt = persona.getPrompt();
        } else {
            systemPrompt = promptLoader.load("questionnaire-system.txt");
        }

        String userPrompt = promptLoader.load("questionnaire-generate.txt");
        String refText = refContext.isEmpty() ? "없음" : String.join("\n---\n", refContext);
        String webContextText = webContext.isEmpty() ? "없음" : String.join("\n---\n", webContext);
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
                        .param("userInput", input)
                        .param("questionCount", String.valueOf(questionCount))
                        .param("format", QNA_FORMAT))
                .call()
                .content();

        log.debug("Persona '{}' raw response length: {}", persona.getName(), content != null ? content.length() : 0);

        List<QuestionAnswer> questions = parser.parseQuestions(content);
        return new PersonaQna(persona.getName(), persona.getRole(), questions);
    }

    // ── Map: 배치별 부분 분석 ──

    private String analyzePartialDocument(List<String> batch, int batchIndex, int totalBatches) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);
        String userPrompt = promptLoader.load("questionnaire-analyze-partial.txt");
        String documentContent = String.join("\n---\n", batch);

        String content = client.prompt()
                .user(u -> u.text(userPrompt)
                        .param("documentContent", documentContent)
                        .param("batchIndex", String.valueOf(batchIndex))
                        .param("totalBatches", String.valueOf(totalBatches)))
                .call()
                .content();

        return content != null ? content : "";
    }

    // ── Reduce: 통합 분석 ──

    private String mergeAnalyses(List<String> partialAnalyses, String userInput) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);
        String userPrompt = promptLoader.load("questionnaire-analyze-merge.txt");
        String joined = String.join("\n\n---\n\n", partialAnalyses);
        String input = userInput != null ? userInput : "";

        String content = client.prompt()
                .user(u -> u.text(userPrompt)
                        .param("partialAnalyses", joined)
                        .param("userInput", input))
                .call()
                .content();

        return content != null ? content : "";
    }

    // ── 배치 1개 (소량 문서)일 때 직접 분석 ──

    private String analyzeFullDocument(List<String> chunks, String userInput) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);
        String userPrompt = promptLoader.load("questionnaire-analyze.txt");
        String documentContent = String.join("\n---\n", chunks);
        String input = userInput != null ? userInput : "";

        String content = client.prompt()
                .user(u -> u.text(userPrompt)
                        .param("documentContent", documentContent)
                        .param("userInput", input))
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
