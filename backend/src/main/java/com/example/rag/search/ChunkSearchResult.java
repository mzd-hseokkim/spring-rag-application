package com.example.rag.search;

import java.util.UUID;

public record ChunkSearchResult(
        UUID chunkId,
        UUID documentId,
        String filename,
        String content,
        int chunkIndex,
        double score
) {}
