package kr.co.mz.ragservice.search;

import kr.co.mz.ragservice.common.PromptLoader;
import kr.co.mz.ragservice.model.ModelClientProvider;
import kr.co.mz.ragservice.model.ModelPurpose;
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

    private final String rerankPrompt;
    private final ModelClientProvider modelProvider;
    private final int rerankCandidates;

    public RerankService(ModelClientProvider modelProvider,
                         @Value("${app.search.rerank-candidates:15}") int rerankCandidates,
                         PromptLoader promptLoader) {
        this.modelProvider = modelProvider;
        this.rerankCandidates = rerankCandidates;
        this.rerankPrompt = promptLoader.load("rerank.txt");
    }

    private ChatClient chatClient() {
        return modelProvider.getChatClient(ModelPurpose.RERANK);
    }

    public List<ChunkSearchResult> rerank(String query, List<ChunkSearchResult> candidates, int topK) {
        List<ChunkSearchResult> toRerank = candidates.size() > rerankCandidates
                ? candidates.subList(0, rerankCandidates)
                : candidates;

        return toRerank.stream()
                .map(chunk -> {
                    double score = scoreChunk(query, chunk);
                    return new ChunkSearchResult(
                            chunk.chunkId(), chunk.documentId(), chunk.filename(),
                            chunk.content(), chunk.parentContent(), chunk.chunkIndex(), score);
                })
                .sorted(Comparator.comparingDouble(ChunkSearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private double scoreChunk(String query, ChunkSearchResult chunk) {
        try {
            String truncatedContent = chunk.content().length() > 500
                    ? chunk.content().substring(0, 500) : chunk.content();

            String response = chatClient().prompt()
                    .user(rerankPrompt.formatted(query, truncatedContent))
                    .call()
                    .content();
            if (response == null) {
                return chunk.score();
            }
            response = response.trim();

            String digits = response.replaceAll("[^0-9.]", "");
            return digits.isEmpty() ? 0.0 : Math.min(10.0, Double.parseDouble(digits));
        } catch (Exception e) {
            log.warn("Rerank scoring failed for chunk {}: {}", chunk.chunkId(), e.getMessage());
            return chunk.score();
        }
    }
}
