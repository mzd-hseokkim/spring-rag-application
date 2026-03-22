package com.example.rag.dashboard;

import com.example.rag.auth.AppUserRepository;
import com.example.rag.conversation.ConversationRepository;
import com.example.rag.document.DocumentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private static final String KEY_INPUT_TOKENS = "inputTokens";
    private static final String KEY_OUTPUT_TOKENS = "outputTokens";

    private final PipelineTraceRepository traceRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final AppUserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final ConversationRepository conversationRepository;

    public DashboardService(PipelineTraceRepository traceRepository,
                             TokenUsageRepository tokenUsageRepository,
                             AppUserRepository userRepository,
                             DocumentRepository documentRepository,
                             ConversationRepository conversationRepository) {
        this.traceRepository = traceRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.conversationRepository = conversationRepository;
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

        return Map.of(
                "chatToday", chatToday,
                "chatWeek", chatWeek,
                "totalUsers", totalUsers,
                "totalDocuments", totalDocs,
                "totalConversations", totalConversations,
                "avgLatencyMs", avgLatency != null ? avgLatency.intValue() : 0
        );
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
    public List<Map<String, Object>> getTokenTrend(int days) {
        LocalDateTime after = LocalDate.now().minusDays(days).atStartOfDay();
        return tokenUsageRepository.sumByDay(after).stream()
                .map(row -> Map.<String, Object>of(
                        "date", row[0].toString(),
                        KEY_INPUT_TOKENS, ((Number) row[1]).longValue(),
                        KEY_OUTPUT_TOKENS, ((Number) row[2]).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTokenByUser(int days) {
        LocalDateTime after = LocalDate.now().minusDays(days).atStartOfDay();
        return tokenUsageRepository.sumByUser(after).stream()
                .map(row -> Map.<String, Object>of(
                        "email", row[0].toString(),
                        "name", row[1].toString(),
                        KEY_INPUT_TOKENS, ((Number) row[2]).longValue(),
                        KEY_OUTPUT_TOKENS, ((Number) row[3]).longValue(),
                        "requestCount", ((Number) row[4]).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTokenByModel(int days) {
        LocalDateTime after = LocalDate.now().minusDays(days).atStartOfDay();
        return tokenUsageRepository.sumByModel(after).stream()
                .map(row -> Map.<String, Object>of(
                        "modelName", row[0].toString(),
                        "purpose", row[1].toString(),
                        KEY_INPUT_TOKENS, ((Number) row[2]).longValue(),
                        KEY_OUTPUT_TOKENS, ((Number) row[3]).longValue(),
                        "requestCount", ((Number) row[4]).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<PipelineTraceEntity> getTraces(Pageable pageable) {
        return traceRepository.findAllByOrderByCreatedAtDesc(pageable);
    }
}
