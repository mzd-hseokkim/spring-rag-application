package com.example.rag.agent;

import com.example.rag.common.PromptLoader;
import com.example.rag.document.Document;
import com.example.rag.document.DocumentRepository;
import com.example.rag.document.DocumentStatus;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class SearchAgent {

    private final String decidePrompt;
    private final ModelClientProvider modelProvider;
    private final DocumentRepository documentRepository;

    public SearchAgent(ModelClientProvider modelProvider,
                       PromptLoader promptLoader,
                       DocumentRepository documentRepository) {
        this.modelProvider = modelProvider;
        this.decidePrompt = promptLoader.load("agent-decide.txt");
        this.documentRepository = documentRepository;
    }

    private ChatClient chatClient() {
        return modelProvider.getChatClient(ModelPurpose.QUERY);
    }

    public AgentDecision decide(String query, UUID userId, boolean includePublicDocs,
                               List<UUID> tagIds, List<UUID> collectionIds) {
        // 사용자의 문서 + (옵션) 공용 문서 조회
        List<Document> documents = includePublicDocs
                ? documentRepository.findSearchableDocuments(DocumentStatus.COMPLETED, userId)
                : documentRepository.findByStatusAndUserId(DocumentStatus.COMPLETED, userId);

        // 태그/컬렉션 필터 (OR 조건: 선택된 태그 중 하나라도 또는 선택된 컬렉션 중 하나라도 매칭)
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

        String docList = documents.isEmpty() ? "(없음)" : documents.stream()
                .map(d -> "- [%s] %s".formatted(d.getId().toString().substring(0, 8), d.getFilename()))
                .collect(Collectors.joining("\n"));

        String response = chatClient().prompt()
                .user(decidePrompt.formatted(docList, query))
                .call()
                .content()
                .trim();

        // 응답 파싱
        String upper = response.toUpperCase();
        if (upper.contains("DIRECT_ANSWER") || upper.contains("DIRECT")) {
            return AgentDecision.directAnswer();
        }
        if (upper.contains("CLARIFY")) {
            return AgentDecision.clarify();
        }

        // SEARCH — 대상 문서 ID 추출
        List<UUID> targetIds = new ArrayList<>();
        for (Document doc : documents) {
            String shortId = doc.getId().toString().substring(0, 8);
            if (response.contains(shortId)) {
                targetIds.add(doc.getId());
            }
        }
        return AgentDecision.search(targetIds);
    }
}
