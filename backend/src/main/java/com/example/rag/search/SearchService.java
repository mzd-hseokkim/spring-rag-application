package com.example.rag.search;

import com.example.rag.search.query.QueryExpander;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class SearchService {

    private static final int SEARCH_LIMIT = 10;
    private static final int FUSION_TOP = 15;  // 리랭킹 전 퓨전 후보 수
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
        // Multi-Query Expansion
        List<String> queries = queryExpander.expand(query);

        // 각 질의에 대해 병렬 검색 후 결과 수집
        List<ChunkSearchResult> allVectorResults = new ArrayList<>();
        List<ChunkSearchResult> allKeywordResults = new ArrayList<>();

        for (String q : queries) {
            CompletableFuture<List<ChunkSearchResult>> vectorFuture =
                    CompletableFuture.supplyAsync(() -> vectorSearchService.search(q, SEARCH_LIMIT));
            CompletableFuture<List<ChunkSearchResult>> keywordFuture =
                    CompletableFuture.supplyAsync(() -> keywordSearchService.search(q, SEARCH_LIMIT));

            allVectorResults.addAll(vectorFuture.join());
            allKeywordResults.addAll(keywordFuture.join());
        }

        // RRF 퓨전 → 리랭킹
        List<ChunkSearchResult> fused = rrfFusionService.fuse(allVectorResults, allKeywordResults, FUSION_TOP);
        return rerankService.rerank(query, fused, TOP_K);
    }
}
