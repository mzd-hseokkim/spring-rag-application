package com.example.rag.agent;

import com.example.rag.common.PromptLoader;
import com.example.rag.conversation.ConversationMessage;
import com.example.rag.conversation.ConversationService;
import com.example.rag.document.Document;
import com.example.rag.document.DocumentRepository;
import com.example.rag.document.DocumentStatus;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class SearchAgent {

    private static final Logger log = LoggerFactory.getLogger(SearchAgent.class);

    private final String decidePrompt;
    private final String analyzePrompt;
    private final ModelClientProvider modelProvider;
    private final DocumentRepository documentRepository;
    private final ConversationService conversationService;
    private final int maxSubQueries;

    public SearchAgent(ModelClientProvider modelProvider,
                       PromptLoader promptLoader,
                       DocumentRepository documentRepository,
                       ConversationService conversationService,
                       @Value("${app.rag.max-sub-queries:3}") int maxSubQueries) {
        this.modelProvider = modelProvider;
        this.decidePrompt = promptLoader.load("agent-decide.txt");
        this.analyzePrompt = promptLoader.load("analyze.txt");
        this.documentRepository = documentRepository;
        this.conversationService = conversationService;
        this.maxSubQueries = maxSubQueries;
    }

    private ChatClient chatClient() {
        return modelProvider.getChatClient(ModelPurpose.QUERY);
    }

    /**
     * 통합 분석 — compress + decide + decompose를 단일 LLM 호출로 수행.
     */
    public AnalysisResult analyze(String sessionId, String message, UUID userId,
                                   boolean includePublicDocs, List<UUID> tagIds, List<UUID> collectionIds) {
        List<Document> documents = getFilteredDocuments(userId, includePublicDocs, tagIds, collectionIds);
        log.info("[SearchAgent] userId={}, includePublicDocs={}, tagIds={}, collectionIds={}, documents.size={}",
                userId, includePublicDocs, tagIds, collectionIds, documents.size());

        String docList = documents.isEmpty() ? "(없음)" : documents.stream()
                .map(d -> "- [%s] %s".formatted(d.getId().toString().substring(0, 8), d.getFilename()))
                .collect(Collectors.joining("\n"));
        log.info("[SearchAgent] docList={}", docList);

        String historyText = buildHistoryText(sessionId);
        String prompt = analyzePrompt.formatted(historyText, docList, message);

        String response = chatClient().prompt()
                .user(prompt)
                .call()
                .content();
        log.info("[SearchAgent] LLM response={}", response);
        if (response == null) {
            return new AnalysisResult(AgentAction.DIRECT_ANSWER, "", List.of(), null);
        }

        return parseAnalysisResponse(response.trim(), documents);
    }

    /**
     * 기존 decide 메서드 — 하위 호환용으로 유지.
     */
    public AgentDecision decide(String query, UUID userId, boolean includePublicDocs,
                               List<UUID> tagIds, List<UUID> collectionIds) {
        List<Document> documents = getFilteredDocuments(userId, includePublicDocs, tagIds, collectionIds);

        String docList = documents.isEmpty() ? "(없음)" : documents.stream()
                .map(d -> "- [%s] %s".formatted(d.getId().toString().substring(0, 8), d.getFilename()))
                .collect(Collectors.joining("\n"));

        String response = chatClient().prompt()
                .user(decidePrompt.formatted(docList, query))
                .call()
                .content();
        if (response == null) {
            return AgentDecision.directAnswer();
        }

        String upper = response.trim().toUpperCase();
        if (upper.contains("DIRECT_ANSWER") || upper.contains("DIRECT")) {
            return AgentDecision.directAnswer();
        }
        if (upper.contains("CLARIFY")) {
            return AgentDecision.clarify();
        }

        List<UUID> targetIds = extractDocumentIds(response, documents);
        return AgentDecision.search(targetIds);
    }

    private AnalysisResult parseAnalysisResponse(String response, List<Document> documents) {
        String actionStr = extractField(response, "ACTION:");
        String queryStr = extractField(response, "QUERY:");
        String docsStr = extractField(response, "DOCUMENTS:");
        String subStr = extractAfterField(response, "SUB_QUERIES:");

        // Action
        AgentAction action = AgentAction.SEARCH;
        if (actionStr.contains("DIRECT_ANSWER") || actionStr.contains("DIRECT")) {
            action = AgentAction.DIRECT_ANSWER;
        } else if (actionStr.contains("CLARIFY")) {
            action = AgentAction.CLARIFY;
        }

        // Search query
        String searchQuery = queryStr.isBlank() ? "" : queryStr;

        // Target documents
        List<UUID> targetIds;
        if (docsStr.isBlank() || docsStr.equalsIgnoreCase("ALL")) {
            targetIds = List.of();
        } else {
            targetIds = extractDocumentIds(docsStr, documents);
        }

        // Sub-queries
        List<String> subQueries = null;
        if (!subStr.isBlank() && !subStr.equalsIgnoreCase("NONE")) {
            subQueries = Arrays.stream(subStr.split("\n"))
                    .map(s -> s.replaceFirst("^[-\\d.]+\\s*", "").trim())
                    .filter(s -> !s.isEmpty() && !s.equalsIgnoreCase("NONE"))
                    .limit(maxSubQueries)
                    .toList();
            if (subQueries.isEmpty()) subQueries = null;
        }

        return new AnalysisResult(action, searchQuery, targetIds, subQueries);
    }

    private String extractField(String text, String field) {
        for (String line : text.split("\n")) {
            if (line.toUpperCase().startsWith(field.toUpperCase())) {
                return line.substring(field.length()).trim();
            }
        }
        return "";
    }

    private String extractAfterField(String text, String field) {
        int idx = text.toUpperCase().indexOf(field.toUpperCase());
        if (idx < 0) return "";
        return text.substring(idx + field.length()).trim();
    }

    private List<UUID> extractDocumentIds(String text, List<Document> documents) {
        List<UUID> ids = new ArrayList<>();
        for (Document doc : documents) {
            String shortId = doc.getId().toString().substring(0, 8);
            if (text.contains(shortId)) {
                ids.add(doc.getId());
            }
        }
        return ids;
    }

    private List<Document> getFilteredDocuments(UUID userId, boolean includePublicDocs,
                                                 List<UUID> tagIds, List<UUID> collectionIds) {
        List<Document> documents = includePublicDocs
                ? documentRepository.findSearchableDocuments(DocumentStatus.COMPLETED, userId)
                : documentRepository.findByStatusAndUserId(DocumentStatus.COMPLETED, userId);

        boolean hasTagFilter = tagIds != null && !tagIds.isEmpty();
        boolean hasColFilter = collectionIds != null && !collectionIds.isEmpty();
        if (hasTagFilter || hasColFilter) {
            documents = documents.stream()
                    .filter(d -> {
                        boolean matchesTag = hasTagFilter
                                && d.getTags().stream().anyMatch(t -> tagIds.contains(t.getId()));
                        boolean matchesCol = hasColFilter
                                && d.getCollections().stream().anyMatch(c -> collectionIds.contains(c.getId()));
                        return matchesTag || matchesCol;
                    })
                    .toList();
        }
        return documents;
    }

    private String buildHistoryText(String sessionId) {
        List<ConversationMessage> history = conversationService.getHistory(sessionId);
        List<ConversationMessage> previous = history.subList(0, Math.max(0, history.size() - 1));
        if (previous.isEmpty()) return "(없음)";
        return previous.stream()
                .map(m -> "%s: %s".formatted(m.role(), m.content()))
                .collect(Collectors.joining("\n"));
    }
}
