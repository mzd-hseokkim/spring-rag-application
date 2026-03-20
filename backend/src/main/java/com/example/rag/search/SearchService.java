package com.example.rag.search;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class SearchService {

    private static final int SEARCH_LIMIT = 10; // 각 검색에서 가져올 후보 수
    private static final int TOP_K = 5;         // 최종 반환 수

    private final VectorSearchService vectorSearchService;
    private final KeywordSearchService keywordSearchService;
    private final RrfFusionService rrfFusionService;

    public SearchService(VectorSearchService vectorSearchService,
                         KeywordSearchService keywordSearchService,
                         RrfFusionService rrfFusionService) {
        this.vectorSearchService = vectorSearchService;
        this.keywordSearchService = keywordSearchService;
        this.rrfFusionService = rrfFusionService;
    }

    public List<ChunkSearchResult> search(String query) {
        // 벡터 검색과 키워드 검색을 병렬 실행
        CompletableFuture<List<ChunkSearchResult>> vectorFuture =
                CompletableFuture.supplyAsync(() -> vectorSearchService.search(query, SEARCH_LIMIT));
        CompletableFuture<List<ChunkSearchResult>> keywordFuture =
                CompletableFuture.supplyAsync(() -> keywordSearchService.search(query, SEARCH_LIMIT));

        List<ChunkSearchResult> vectorResults = vectorFuture.join();
        List<ChunkSearchResult> keywordResults = keywordFuture.join();

        return rrfFusionService.fuse(vectorResults, keywordResults, TOP_K);
    }
}
