package com.example.rag.search;

import java.util.UUID;

public record ChunkSearchResult(
        UUID chunkId,
        UUID documentId,
        String filename,
        String content,
        String parentContent,   // parent 청크 내용 (없으면 null)
        int chunkIndex,
        double score
) {
    /** parent content가 있으면 parent를, 없으면 자기 content를 반환 */
    public String contextContent() {
        return parentContent != null ? parentContent : content;
    }
}
