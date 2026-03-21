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

    public AgentDecision decide(String query) {
        // 업로드된 문서 목록 조회
        List<Document> documents = documentRepository.findByStatus(DocumentStatus.COMPLETED);
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
