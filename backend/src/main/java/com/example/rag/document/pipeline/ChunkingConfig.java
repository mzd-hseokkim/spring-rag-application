package com.example.rag.document.pipeline;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChunkingConfig {

    @Bean
    public ChunkingStrategy chunkingStrategy(
            @Value("${app.chunking.mode:fixed}") String mode,
            @Value("${app.chunking.fixed.chunk-size:1000}") int fixedChunkSize,
            @Value("${app.chunking.fixed.overlap:200}") int fixedOverlap,
            @Value("${app.chunking.semantic.buffer-size:1}") int bufferSize,
            @Value("${app.chunking.semantic.breakpoint-percentile:90}") double breakpointPercentile,
            @Value("${app.chunking.semantic.min-chunk-size:200}") int minChunkSize,
            @Value("${app.chunking.semantic.max-chunk-size:1500}") int maxChunkSize,
            EmbeddingModel embeddingModel) {

        if ("semantic".equalsIgnoreCase(mode)) {
            return new SemanticChunkingStrategy(
                    embeddingModel, bufferSize, breakpointPercentile, minChunkSize, maxChunkSize);
        }
        return new FixedSizeChunkingStrategy(fixedChunkSize, fixedOverlap);
    }
}
