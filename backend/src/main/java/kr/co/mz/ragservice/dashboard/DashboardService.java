package kr.co.mz.ragservice.dashboard;

import kr.co.mz.ragservice.auth.AppUserRepository;
import kr.co.mz.ragservice.conversation.ConversationRepository;
import kr.co.mz.ragservice.document.DocumentRepository;
import kr.co.mz.ragservice.generation.GenerationJobRepository;
import kr.co.mz.ragservice.generation.GenerationStatus;
import kr.co.mz.ragservice.questionnaire.QuestionnaireJobRepository;
import kr.co.mz.ragservice.questionnaire.QuestionnaireStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private static final String KEY_INPUT_TOKENS = "inputTokens";
    private static final String KEY_OUTPUT_TOKENS = "outputTokens";
    private static final String KEY_REQUEST_COUNT = "requestCount";
    private static final String KEY_PURPOSE = "purpose";
    private static final String KEY_ESTIMATED_COST = "estimatedCost";

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final PipelineTraceRepository traceRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final AppUserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final ConversationRepository conversationRepository;
    private final GenerationJobRepository generationJobRepository;
    private final QuestionnaireJobRepository questionnaireJobRepository;
    private final ModelPricingRepository modelPricingRepository;
    private final GenerationTraceRepository generationTraceRepository;

    public DashboardService(PipelineTraceRepository traceRepository,
                             TokenUsageRepository tokenUsageRepository,
                             AppUserRepository userRepository,
                             DocumentRepository documentRepository,
                             ConversationRepository conversationRepository,
                             GenerationJobRepository generationJobRepository,
                             QuestionnaireJobRepository questionnaireJobRepository,
                             ModelPricingRepository modelPricingRepository,
                             GenerationTraceRepository generationTraceRepository) {
        this.traceRepository = traceRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.conversationRepository = conversationRepository;
        this.generationJobRepository = generationJobRepository;
        this.questionnaireJobRepository = questionnaireJobRepository;
        this.modelPricingRepository = modelPricingRepository;
        this.generationTraceRepository = generationTraceRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOverview() {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        LocalDateTime weekAgo = today.minusDays(7);

        long chatToday = traceRepository.countByCreatedAtAfter(today);
        long chatWeek = traceRepository.countByCreatedAtAfter(weekAgo);
        long totalUsers = userRepository.count();
        long totalDocs = documentRepository.count();
        long totalConversations = conversationRepository.count();
        Double avgLatency = traceRepository.avgLatency(weekAgo);

        long generationTotal = generationJobRepository.count();
        long generationToday = generationJobRepository.countByCreatedAtAfter(today);
        long generationFailed = generationJobRepository.countByStatus(GenerationStatus.FAILED);
        long questionnaireTotal = questionnaireJobRepository.count();
        long questionnaireToday = questionnaireJobRepository.countByCreatedAtAfter(today);
        long questionnaireFailed = questionnaireJobRepository.countByStatus(QuestionnaireStatus.FAILED);

        Map<String, Object> result = new HashMap<>();
        result.put("chatToday", chatToday);
        result.put("chatWeek", chatWeek);
        result.put("totalUsers", totalUsers);
        result.put("totalDocuments", totalDocs);
        result.put("totalConversations", totalConversations);
        result.put("avgLatencyMs", avgLatency != null ? avgLatency.intValue() : 0);
        result.put("generationTotal", generationTotal);
        result.put("generationToday", generationToday);
        result.put("generationFailed", generationFailed);
        result.put("questionnaireTotal", questionnaireTotal);
        result.put("questionnaireToday", questionnaireToday);
        result.put("questionnaireFailed", questionnaireFailed);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getChatTrend(int days) {
        LocalDateTime after = LocalDate.now().minusDays(days).atStartOfDay();
        return traceRepository.countByDay(after).stream()
                .map(row -> Map.<String, Object>of(
                        "date", row[0].toString(),
                        "count", ((Number) row[1]).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAgentDistribution() {
        LocalDateTime after = LocalDate.now().minusDays(30).atStartOfDay();
        return traceRepository.countByAgentAction(after).stream()
                .map(row -> Map.<String, Object>of(
                        "action", row[0] != null ? row[0].toString() : "UNKNOWN",
                        "count", ((Number) row[1]).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTokenTrend(int days, String purpose) {
        LocalDateTime after = LocalDate.now().minusDays(days).atStartOfDay();
        List<Object[]> rows = (purpose == null)
                ? tokenUsageRepository.sumByDay(after)
                : tokenUsageRepository.sumByDayAndPurpose(after, purpose);
        return rows.stream()
                .map(row -> Map.<String, Object>of(
                        "date", row[0].toString(),
                        KEY_INPUT_TOKENS, ((Number) row[1]).longValue(),
                        KEY_OUTPUT_TOKENS, ((Number) row[2]).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTokenByUser(int days, String purpose) {
        LocalDateTime after = LocalDate.now().minusDays(days).atStartOfDay();
        List<Object[]> rows = (purpose == null)
                ? tokenUsageRepository.sumByUser(after)
                : tokenUsageRepository.sumByUserAndPurpose(after, purpose);
        return rows.stream()
                .map(row -> Map.<String, Object>of(
                        "email", row[0].toString(),
                        "name", row[1].toString(),
                        KEY_INPUT_TOKENS, ((Number) row[2]).longValue(),
                        KEY_OUTPUT_TOKENS, ((Number) row[3]).longValue(),
                        KEY_REQUEST_COUNT, ((Number) row[4]).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTokenByModel(int days, String purpose) {
        LocalDateTime after = LocalDate.now().minusDays(days).atStartOfDay();
        List<Object[]> rows = (purpose == null)
                ? tokenUsageRepository.sumByModel(after)
                : tokenUsageRepository.sumByModelAndPurpose(after, purpose);
        return rows.stream()
                .map(row -> Map.<String, Object>of(
                        "modelName", row[0].toString(),
                        KEY_PURPOSE, row[1].toString(),
                        KEY_INPUT_TOKENS, ((Number) row[2]).longValue(),
                        KEY_OUTPUT_TOKENS, ((Number) row[3]).longValue(),
                        KEY_REQUEST_COUNT, ((Number) row[4]).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTokenByPurpose(int days) {
        LocalDateTime after = LocalDate.now().minusDays(days).atStartOfDay();
        return tokenUsageRepository.sumByPurpose(after).stream()
                .map(row -> Map.<String, Object>of(
                        KEY_PURPOSE, row[0].toString(),
                        KEY_INPUT_TOKENS, ((Number) row[1]).longValue(),
                        KEY_OUTPUT_TOKENS, ((Number) row[2]).longValue(),
                        KEY_REQUEST_COUNT, ((Number) row[3]).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getGenerationTrend(int days) {
        LocalDateTime after = LocalDate.now().minusDays(days).atStartOfDay();
        List<Object[]> genRows = generationJobRepository.countByDay(after);
        List<Object[]> questRows = questionnaireJobRepository.countByDay(after);

        Map<String, long[]> merged = new java.util.LinkedHashMap<>();
        for (Object[] row : genRows) {
            merged.computeIfAbsent(row[0].toString(), k -> new long[2])[0] = ((Number) row[1]).longValue();
        }
        for (Object[] row : questRows) {
            merged.computeIfAbsent(row[0].toString(), k -> new long[2])[1] = ((Number) row[1]).longValue();
        }

        return merged.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> Map.<String, Object>of(
                        "date", e.getKey(),
                        "generationCount", e.getValue()[0],
                        "questionnaireCount", e.getValue()[1]))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTokenCost(int days) {
        LocalDateTime after = LocalDate.now().minusDays(days).atStartOfDay();
        Map<String, ModelPricingEntity> pricingMap = modelPricingRepository.findAll().stream()
                .collect(Collectors.toMap(ModelPricingEntity::getModelName, Function.identity()));

        return tokenUsageRepository.sumByModel(after).stream()
                .map(row -> {
                    String modelName = row[0].toString();
                    String purpose = row[1].toString();
                    long inputTokens = ((Number) row[2]).longValue();
                    long outputTokens = ((Number) row[3]).longValue();
                    long requestCount = ((Number) row[4]).longValue();

                    ModelPricingEntity pricing = pricingMap.get(modelName);
                    BigDecimal cost = BigDecimal.ZERO;
                    String currency = "USD";
                    if (pricing != null) {
                        BigDecimal inputCost = pricing.getInputPricePer1m()
                                .multiply(BigDecimal.valueOf(inputTokens))
                                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
                        BigDecimal outputCost = pricing.getOutputPricePer1m()
                                .multiply(BigDecimal.valueOf(outputTokens))
                                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
                        cost = inputCost.add(outputCost);
                        currency = pricing.getCurrency();
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("modelName", modelName);
                    result.put(KEY_PURPOSE, purpose);
                    result.put(KEY_INPUT_TOKENS, inputTokens);
                    result.put(KEY_OUTPUT_TOKENS, outputTokens);
                    result.put(KEY_REQUEST_COUNT, requestCount);
                    result.put(KEY_ESTIMATED_COST, cost.setScale(4, RoundingMode.HALF_UP));
                    result.put("currency", currency);
                    return result;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTokenCostByUser(int days) {
        LocalDateTime after = LocalDate.now().minusDays(days).atStartOfDay();
        Map<String, ModelPricingEntity> pricingMap = modelPricingRepository.findAll().stream()
                .collect(Collectors.toMap(ModelPricingEntity::getModelName, Function.identity()));

        // 유저+모델별 집계 → 유저별 비용 합산
        Map<String, Map<String, Object>> userCostMap = new java.util.LinkedHashMap<>();
        for (Object[] row : tokenUsageRepository.sumByUserAndModel(after)) {
            String email = row[0].toString();
            String name = row[1].toString();
            String modelName = row[2].toString();
            long inputTokens = ((Number) row[3]).longValue();
            long outputTokens = ((Number) row[4]).longValue();

            Map<String, Object> entry = userCostMap.computeIfAbsent(email, k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("email", email);
                m.put("name", name);
                m.put(KEY_INPUT_TOKENS, 0L);
                m.put(KEY_OUTPUT_TOKENS, 0L);
                m.put(KEY_ESTIMATED_COST, BigDecimal.ZERO);
                m.put("currency", "USD");
                return m;
            });

            entry.put(KEY_INPUT_TOKENS, (long) entry.get(KEY_INPUT_TOKENS) + inputTokens);
            entry.put(KEY_OUTPUT_TOKENS, (long) entry.get(KEY_OUTPUT_TOKENS) + outputTokens);

            pricingMap.computeIfPresent(modelName, (k, pricing) -> {
                BigDecimal inputCost = pricing.getInputPricePer1m()
                        .multiply(BigDecimal.valueOf(inputTokens))
                        .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
                BigDecimal outputCost = pricing.getOutputPricePer1m()
                        .multiply(BigDecimal.valueOf(outputTokens))
                        .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
                entry.put(KEY_ESTIMATED_COST, ((BigDecimal) entry.get(KEY_ESTIMATED_COST)).add(inputCost).add(outputCost));
                return pricing;
            });
        }

        List<Map<String, Object>> sorted = userCostMap.values().stream()
                .sorted((a, b) -> ((BigDecimal) b.get(KEY_ESTIMATED_COST)).compareTo((BigDecimal) a.get(KEY_ESTIMATED_COST)))
                .toList();
        sorted.forEach(m -> m.put(KEY_ESTIMATED_COST, ((BigDecimal) m.get(KEY_ESTIMATED_COST)).setScale(4, RoundingMode.HALF_UP)));
        return sorted;
    }

    @Transactional(readOnly = true)
    public Page<PipelineTraceEntity> getTraces(Pageable pageable) {
        return traceRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public Page<GenerationTraceEntity> getGenerationTraces(Pageable pageable) {
        return generationTraceRepository.findAllByOrderByStartedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public List<GenerationTraceEntity> getGenerationTracesByJob(java.util.UUID jobId) {
        return generationTraceRepository.findByJobIdOrderByStartedAtAsc(jobId);
    }
}
