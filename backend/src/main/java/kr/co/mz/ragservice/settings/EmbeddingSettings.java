package kr.co.mz.ragservice.settings;

import java.util.Map;

public record EmbeddingSettings(int batchSize, int concurrency) {

    public static EmbeddingSettings fromMap(Map<String, Object> map) {
        return new EmbeddingSettings(
                toInt(map.getOrDefault("batchSize", 32)),
                toInt(map.getOrDefault("concurrency", 2))
        );
    }

    public Map<String, Object> toMap() {
        return Map.of("batchSize", batchSize, "concurrency", concurrency);
    }

    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }
}
