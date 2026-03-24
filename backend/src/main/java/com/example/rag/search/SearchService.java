package com.example.rag.search;

import com.example.rag.search.query.QueryExpander;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class SearchService {

    private static final int SEARCH_LIMIT = 10;
    private static final int FUSION_TOP = 15;
    private static final int TOP_K = 5;

    private final VectorSearchService vectorSearchService;
    private final KeywordSearchService keywordSearchService;
    private final RrfFusionService rrfFusionService;
    private final RerankService rerankService;
    private final QueryExpander queryExpander;

    public SearchService(VectorSearchService vectorSearchService,
                         KeywordSearchService keywordSearchService,
                         RrfFusionService rrfFusionService,
                         RerankService rerankService,
                         QueryExpander queryExpander) {
        this.vectorSearchService = vectorSearchService;
        this.keywordSearchService = keywordSearchService;
        this.rrfFusionService = rrfFusionService;
        this.rerankService = rerankService;
        this.queryExpander = queryExpander;
    }

    public List<ChunkSearchResult> search(String query) {
        return search(query, List.of());
    }

    public List<ChunkSearchResult> search(String query, List<UUID> documentIds) {
        List<String> queries = queryExpander.expand(query);

        List<ChunkSearchResult> allVectorResults = new ArrayList<>();
        List<ChunkSearchResult> allKeywordResults = new ArrayList<>();

        for (String q : queries) {
            CompletableFuture<List<ChunkSearchResult>> vectorFuture =
                    CompletableFuture.supplyAsync(() -> vectorSearchService.search(q, SEARCH_LIMIT, documentIds));
            CompletableFuture<List<ChunkSearchResult>> keywordFuture =
                    CompletableFuture.supplyAsync(() -> keywordSearchService.search(q, SEARCH_LIMIT, documentIds));

            allVectorResults.addAll(vectorFuture.join());
            allKeywordResults.addAll(keywordFuture.join());
        }

        List<ChunkSearchResult> fused = rrfFusionService.fuse(allVectorResults, allKeywordResults, FUSION_TOP);
        return rerankService.rerank(query, fused, TOP_K);
    }

    /**
     * 쿼리 확장 없이 직접 벡터+키워드 검색만 수행한다.
     * 이미 잘 설계된 검색 키워드(ANALYSIS_QUERIES 등)에 적합.
     */
    public List<ChunkSearchResult> searchDirect(String query, List<UUID> documentIds) {
        CompletableFuture<List<ChunkSearchResult>> vectorFuture =
                CompletableFuture.supplyAsync(() -> vectorSearchService.search(query, SEARCH_LIMIT, documentIds));
        CompletableFuture<List<ChunkSearchResult>> keywordFuture =
                CompletableFuture.supplyAsync(() -> keywordSearchService.search(query, SEARCH_LIMIT, documentIds));

        List<ChunkSearchResult> fused = rrfFusionService.fuse(vectorFuture.join(), keywordFuture.join(), FUSION_TOP);
        return fused.stream().limit(TOP_K).toList();
    }
}
