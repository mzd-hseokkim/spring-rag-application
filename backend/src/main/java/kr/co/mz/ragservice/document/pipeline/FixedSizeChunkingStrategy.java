package kr.co.mz.ragservice.document.pipeline;

import java.util.ArrayList;
import java.util.List;

public class FixedSizeChunkingStrategy implements ChunkingStrategy {

    private final int chunkSize;
    private final int overlap;

    public FixedSizeChunkingStrategy(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

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

            int nextStart = end - overlap;
            start = Math.max(nextStart, start + 1);
            if (end >= text.length()) break;
        }

        return chunks;
    }
}
