package com.example.rag.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class SettingsService {

    private final SystemSettingsRepository repository;

    public SettingsService(SystemSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ChunkingSettings getChunkingSettings() {
        return repository.findById("chunking")
                .map(s -> ChunkingSettings.fromMap(s.getValue()))
                .orElseGet(() -> ChunkingSettings.fromMap(Map.of()));
    }

    @Transactional
    public ChunkingSettings updateChunkingSettings(ChunkingSettings settings) {
        SystemSettings entity = repository.findById("chunking")
                .orElse(new SystemSettings("chunking", Map.of()));
        entity.setValue(settings.toMap());
        repository.save(entity);
        return settings;
    }

    @Transactional(readOnly = true)
    public EmbeddingSettings getEmbeddingSettings() {
        return repository.findById("embedding")
                .map(s -> EmbeddingSettings.fromMap(s.getValue()))
                .orElseGet(() -> EmbeddingSettings.fromMap(Map.of()));
    }

    @Transactional
    public EmbeddingSettings updateEmbeddingSettings(EmbeddingSettings settings) {
        SystemSettings entity = repository.findById("embedding")
                .orElse(new SystemSettings("embedding", Map.of()));
        entity.setValue(settings.toMap());
        repository.save(entity);
        return settings;
    }
}
