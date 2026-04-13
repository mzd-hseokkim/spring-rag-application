package kr.co.mz.ragservice.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class SettingsService {

    private static final String CHUNKING_KEY = "chunking";
    private static final String EMBEDDING_KEY = "embedding";

    private final SystemSettingsRepository repository;

    public SettingsService(SystemSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ChunkingSettings getChunkingSettings() {
        return repository.findById(CHUNKING_KEY)
                .map(s -> ChunkingSettings.fromMap(s.getValue()))
                .orElseGet(() -> ChunkingSettings.fromMap(Map.of()));
    }

    @Transactional
    public ChunkingSettings updateChunkingSettings(ChunkingSettings settings) {
        SystemSettings entity = repository.findById(CHUNKING_KEY)
                .orElse(new SystemSettings(CHUNKING_KEY, Map.of()));
        entity.setValue(settings.toMap());
        repository.save(entity);
        return settings;
    }

    @Transactional(readOnly = true)
    public EmbeddingSettings getEmbeddingSettings() {
        return repository.findById(EMBEDDING_KEY)
                .map(s -> EmbeddingSettings.fromMap(s.getValue()))
                .orElseGet(() -> EmbeddingSettings.fromMap(Map.of()));
    }

    @Transactional
    public EmbeddingSettings updateEmbeddingSettings(EmbeddingSettings settings) {
        SystemSettings entity = repository.findById(EMBEDDING_KEY)
                .orElse(new SystemSettings(EMBEDDING_KEY, Map.of()));
        entity.setValue(settings.toMap());
        repository.save(entity);
        return settings;
    }
}
