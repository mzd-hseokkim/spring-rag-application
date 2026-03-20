package com.example.rag.document.pipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChunkingStrategy {

    private final int chunkSize;
    private final int overlap;

    public ChunkingStrategy(
            @Value("${app.chunking.chunk-size:1000}") int chunkSize,
            @Value("${app.chunking.overlap:200}") int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            // 문단 경계(줄바꿈 2개)를 존중하여 분할 지점 조정
            if (end < text.length()) {
                int paragraphBreak = text.lastIndexOf("\n\n", end);
                if (paragraphBreak > start) {
                    end = paragraphBreak + 2;
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            // 다음 시작점: 최소한 1글자는 전진해야 무한루프 방지
            int nextStart = end - overlap;
            start = Math.max(nextStart, start + 1);
            if (end >= text.length()) break;
        }

        return chunks;
    }
}
