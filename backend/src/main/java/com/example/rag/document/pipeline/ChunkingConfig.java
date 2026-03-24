package com.example.rag.document.pipeline;

import com.example.rag.model.ModelClientProvider;
import com.example.rag.settings.ChunkingSettings;
import com.example.rag.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChunkingConfig {

    /**
     * 기본 ChunkingStrategy 빈 — ChatService 등에서 사용.
     * IngestionPipeline은 매 실행마다 DB 설정을 직접 읽어 strategy를 생성한다.
     */
    @Bean
    public ChunkingStrategy chunkingStrategy(SettingsService settingsService,
                                              ModelClientProvider modelProvider) {
        ChunkingSettings s = settingsService.getChunkingSettings();
        if ("semantic".equalsIgnoreCase(s.mode())) {
            return new SemanticChunkingStrategy(
                    modelProvider::getEmbeddingModel,
                    s.semanticBufferSize(),
                    s.semanticBreakpointPercentile(),
                    s.semanticMinChunkSize(),
                    s.semanticMaxChunkSize());
        }
        return new FixedSizeChunkingStrategy(s.fixedChunkSize(), s.fixedOverlap());
    }
}
