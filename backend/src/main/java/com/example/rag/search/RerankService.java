package com.example.rag.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private static final String RERANK_PROMPT = """
            질문과 문서 조각의 관련성을 0~10 점수로 평가하세요. 숫자만 답하세요.

            질문: %s
            문서: %s

            점수:
            """;

    private final ChatClient chatClient;
    private final int rerankCandidates;

    public RerankService(ChatClient.Builder chatClientBuilder,
                         @Value("${app.search.rerank-candidates:15}") int rerankCandidates) {
        this.chatClient = chatClientBuilder.build();
        this.rerankCandidates = rerankCandidates;
    }

    public List<ChunkSearchResult> rerank(String query, List<ChunkSearchResult> candidates, int topK) {
        // 후보를 제한
        List<ChunkSearchResult> toRerank = candidates.size() > rerankCandidates
                ? candidates.subList(0, rerankCandidates)
                : candidates;

        return toRerank.stream()
                .map(chunk -> {
                    double score = scoreChunk(query, chunk);
                    return new ChunkSearchResult(
                            chunk.chunkId(), chunk.documentId(), chunk.filename(),
                            chunk.content(), chunk.chunkIndex(), score);
                })
                .sorted(Comparator.comparingDouble(ChunkSearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private double scoreChunk(String query, ChunkSearchResult chunk) {
        try {
            String truncatedContent = chunk.content().length() > 500
                    ? chunk.content().substring(0, 500) : chunk.content();

            String response = chatClient.prompt()
                    .user(RERANK_PROMPT.formatted(query, truncatedContent))
                    .call()
                    .content()
                    .trim();

            // 숫자만 추출
            String digits = response.replaceAll("[^0-9.]", "");
            return digits.isEmpty() ? 0.0 : Math.min(10.0, Double.parseDouble(digits));
        } catch (Exception e) {
            log.warn("Rerank scoring failed for chunk {}: {}", chunk.chunkId(), e.getMessage());
            return chunk.score(); // 실패 시 기존 점수 유지
        }
    }
}
