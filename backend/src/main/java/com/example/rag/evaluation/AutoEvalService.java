package com.example.rag.evaluation;

import com.example.rag.common.PromptLoader;
import com.example.rag.document.DocumentChunk;
import com.example.rag.document.DocumentChunkRepository;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import com.example.rag.search.ChunkSearchResult;
import com.example.rag.search.SearchService;
import com.example.rag.search.query.QueryCompressor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class AutoEvalService {

    private static final Logger log = LoggerFactory.getLogger(AutoEvalService.class);
    private static final int LEASE_SECONDS = 30;
    private static final int HEARTBEAT_SECONDS = 10;

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    private final EvalRunRepository runRepository;
    private final EvalQuestionRepository questionRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ModelClientProvider modelProvider;
    private final SearchService searchService;
    private final QueryCompressor queryCompressor;
    private final ObjectMapper objectMapper;
    private final String questionGenPrompt;
    private final String judgePrompt;
    private final String ragSystemPrompt;

    public AutoEvalService(EvalRunRepository runRepository,
                            EvalQuestionRepository questionRepository,
                            DocumentChunkRepository chunkRepository,
                            ModelClientProvider modelProvider,
                            SearchService searchService,
                            QueryCompressor queryCompressor,
                            ObjectMapper objectMapper,
                            PromptLoader promptLoader) {
        this.runRepository = runRepository;
        this.questionRepository = questionRepository;
        this.chunkRepository = chunkRepository;
        this.modelProvider = modelProvider;
        this.searchService = searchService;
        this.queryCompressor = queryCompressor;
        this.objectMapper = objectMapper;
        this.questionGenPrompt = promptLoader.load("eval-question-gen.txt");
        this.judgePrompt = promptLoader.load("eval-judge.txt");
        this.ragSystemPrompt = promptLoader.load("rag-system.txt");
    }

    // --- 실행 관리 ---

    @Transactional(readOnly = true)
    public List<EvalRunEntity> listRuns() {
        return runRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public EvalRunEntity getRun(UUID id) {
        return runRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("평가 실행을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<EvalQuestionEntity> getQuestions(UUID runId) {
        return questionRepository.findByEvalRunIdOrderByCreatedAt(runId);
    }

    @Transactional(readOnly = true)
    public EvalQuestionEntity getQuestionDetail(java.util.UUID id) {
        return questionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("질문을 찾을 수 없습니다."));
    }

    @Transactional
    public EvalRunEntity createRun(String name) {
        return runRepository.save(new EvalRunEntity(name));
    }

    @Transactional
    public void deleteRun(UUID id) {
        runRepository.deleteById(id);
    }

    // --- Lease 관리 ---

    private void acquireLease(UUID runId, String status) {
        EvalRunEntity run = runRepository.findById(runId).orElseThrow();
        run.setStatus(status);
        run.setLeasedBy(instanceId);
        run.setLeasedUntil(LocalDateTime.now().plusSeconds(LEASE_SECONDS));
        runRepository.save(run);
    }

    private void renewLease(UUID runId) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setLeasedUntil(LocalDateTime.now().plusSeconds(LEASE_SECONDS));
            runRepository.save(run);
        });
    }

    private void releaseLease(UUID runId) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setLeasedUntil(null);
            run.setLeasedBy(null);
            runRepository.save(run);
        });
    }

    private ScheduledFuture<?> startHeartbeat(UUID runId) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        return scheduler.scheduleAtFixedRate(() -> {
            try {
                renewLease(runId);
            } catch (Exception e) {
                log.warn("Lease renewal failed for run {}: {}", runId, e.getMessage());
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    // --- 질문 자동 생성 ---

    @Async("ingestionExecutor")
    public void generateQuestions(UUID runId, List<UUID> documentIds, int questionsPerChunk) {
        ScheduledFuture<?> heartbeat = null;
        try {
            acquireLease(runId, "GENERATING");
            heartbeat = startHeartbeat(runId);

            for (UUID docId : documentIds) {
                List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
                List<DocumentChunk> parentChunks = chunks.stream()
                        .filter(c -> c.getParentChunkId() == null)
                        .toList();

                for (DocumentChunk chunk : parentChunks) {
                    try {
                        String prompt = questionGenPrompt.formatted(questionsPerChunk, chunk.getContent());
                        String response = modelProvider.getChatClient(ModelPurpose.QUERY)
                                .prompt().user(prompt).call().content();

                        List<Map<String, String>> qas = parseQaList(response);
                        for (Map<String, String> qa : qas) {
                            questionRepository.save(new EvalQuestionEntity(
                                    runId, docId,
                                    qa.getOrDefault("question", ""),
                                    qa.getOrDefault("expectedAnswer", ""),
                                    qa.getOrDefault("type", "FACTUAL")));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to generate questions for chunk {}: {}", chunk.getId(), e.getMessage());
                    }
                }
            }

            long total = questionRepository.countByEvalRunId(runId);
            EvalRunEntity run = runRepository.findById(runId).orElseThrow();
            run.setTotalQuestions((int) total);
            run.setStatus("READY");
            runRepository.save(run);
            releaseLease(runId);

        } catch (Exception e) {
            log.error("Question generation failed for run {}: {}", runId, e.getMessage());
            updateStatus(runId, "FAILED");
            releaseLease(runId);
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
        }
    }

    // --- 평가 실행 (Resume 지원) ---

    @Async("ingestionExecutor")
    public void executeRun(UUID runId) {
        ScheduledFuture<?> heartbeat = null;
        try {
            acquireLease(runId, "RUNNING");
            heartbeat = startHeartbeat(runId);

            // PENDING 상태인 질문만 처리 (Resume: 이전에 완료된 건 건너뜀)
            List<EvalQuestionEntity> pendingQuestions = questionRepository
                    .findByEvalRunIdOrderByCreatedAt(runId).stream()
                    .filter(q -> "PENDING".equals(q.getStatus()))
                    .toList();

            for (EvalQuestionEntity q : pendingQuestions) {
                try {
                    RagResult result = callRag(q.getQuestion());
                    q.setActualResponse(result.response());
                    q.setRetrievedContext(result.context());

                    String judgeInput = judgePrompt.formatted(
                            q.getQuestion(), q.getExpectedAnswer(),
                            result.response(), truncate(result.context(), 2000));
                    String judgeResponse = modelProvider.getChatClient(ModelPurpose.QUERY)
                            .prompt().user(judgeInput).call().content();

                    Map<String, Object> scores = parseJudgeResult(judgeResponse);
                    q.setFaithfulness(toDouble(scores.get("faithfulness")));
                    q.setRelevance(toDouble(scores.get("relevance")));
                    q.setCorrectness(toDouble(scores.get("correctness")));
                    q.setJudgeComment((String) scores.getOrDefault("comment", ""));
                    q.setStatus("COMPLETED");

                } catch (Exception e) {
                    log.warn("Eval failed for question {}: {}", q.getId(), e.getMessage());
                    q.setStatus("FAILED");
                    q.setJudgeComment("평가 실패: " + e.getMessage());
                }

                questionRepository.save(q);

                // completedQuestions 갱신 (PENDING이 아닌 것의 수)
                long completed = questionRepository.findByEvalRunIdOrderByCreatedAt(runId).stream()
                        .filter(x -> !"PENDING".equals(x.getStatus()))
                        .count();
                EvalRunEntity run = runRepository.findById(runId).orElseThrow();
                run.setCompletedQuestions((int) completed);
                runRepository.save(run);
            }

            // 완료 처리
            EvalRunEntity run = runRepository.findById(runId).orElseThrow();
            run.setAvgFaithfulness(questionRepository.avgFaithfulness(runId));
            run.setAvgRelevance(questionRepository.avgRelevance(runId));
            run.setAvgCorrectness(questionRepository.avgCorrectness(runId));
            run.setStatus("COMPLETED");
            run.setCompletedAt(LocalDateTime.now());
            runRepository.save(run);
            releaseLease(runId);

        } catch (Exception e) {
            log.error("Eval run failed for {}: {}", runId, e.getMessage());
            updateStatus(runId, "FAILED");
            releaseLease(runId);
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
        }
    }

    // --- Stale 작업 복구 (스케줄러에서 호출) ---

    public List<EvalRunEntity> findStaleRuns() {
        return runRepository.findStaleRuns(LocalDateTime.now());
    }

    // --- 내부 헬퍼 ---

    private RagResult callRag(String question) {
        String searchQuery = queryCompressor.compress("eval-session", question);
        List<ChunkSearchResult> results = searchService.search(searchQuery, List.of());

        String context = results.stream()
                .map(r -> "[%s, 청크 %d]\n%s".formatted(r.filename(), r.chunkIndex(), r.contextContent()))
                .collect(Collectors.joining("\n\n"));

        String response = modelProvider.getChatClient(ModelPurpose.CHAT).prompt()
                .system(ragSystemPrompt)
                .user("[컨텍스트]\n%s\n\n질문: %s".formatted(context, question))
                .call()
                .content();

        return new RagResult(response, context);
    }

    private void updateStatus(UUID runId, String status) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(status);
            runRepository.save(run);
        });
    }

    private List<Map<String, String>> parseQaList(String json) {
        try {
            String cleaned = json.strip();
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            return objectMapper.readValue(cleaned, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse QA list: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> parseJudgeResult(String json) {
        try {
            String cleaned = json.strip();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            return objectMapper.readValue(cleaned, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse judge result: {}", e.getMessage());
            return Map.of();
        }
    }

    private Double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        return null;
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    record RagResult(String response, String context) {}
}
