package com.example.rag.search;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RrfFusionService {

    private static final int RRF_K = 60;

    public List<ChunkSearchResult> fuse(List<ChunkSearchResult> vectorResults,
                                        List<ChunkSearchResult> keywordResults,
                                        int topK) {
        Map<UUID, Double> scores = new HashMap<>();
        Map<UUID, ChunkSearchResult> chunks = new HashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            ChunkSearchResult r = vectorResults.get(i);
            scores.merge(r.chunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
            chunks.putIfAbsent(r.chunkId(), r);
        }

        for (int i = 0; i < keywordResults.size(); i++) {
            ChunkSearchResult r = keywordResults.get(i);
            scores.merge(r.chunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
            chunks.putIfAbsent(r.chunkId(), r);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    ChunkSearchResult original = chunks.get(e.getKey());
                    return new ChunkSearchResult(
                            original.chunkId(), original.documentId(), original.filename(),
                            original.content(), original.chunkIndex(), e.getValue());
                })
                .toList();
    }
}
