package kr.co.mz.ragservice.settings;

import java.util.Map;

/**
 * Runtime-configurable chunking parameters backed by system_settings table.
 */
public record ChunkingSettings(
        String mode,
        int fixedChunkSize,
        int fixedOverlap,
        int semanticBufferSize,
        double semanticBreakpointPercentile,
        int semanticMinChunkSize,
        int semanticMaxChunkSize,
        int childChunkSize,
        int childOverlap
) {
    public static ChunkingSettings fromMap(Map<String, Object> map) {
        return new ChunkingSettings(
                (String) map.getOrDefault("mode", "semantic"),
                toInt(map.getOrDefault("fixedChunkSize", 1000)),
                toInt(map.getOrDefault("fixedOverlap", 200)),
                toInt(map.getOrDefault("semanticBufferSize", 1)),
                toDouble(map.getOrDefault("semanticBreakpointPercentile", 90.0)),
                toInt(map.getOrDefault("semanticMinChunkSize", 200)),
                toInt(map.getOrDefault("semanticMaxChunkSize", 1500)),
                toInt(map.getOrDefault("childChunkSize", 500)),
                toInt(map.getOrDefault("childOverlap", 100))
        );
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "mode", mode,
                "fixedChunkSize", fixedChunkSize,
                "fixedOverlap", fixedOverlap,
                "semanticBufferSize", semanticBufferSize,
                "semanticBreakpointPercentile", semanticBreakpointPercentile,
                "semanticMinChunkSize", semanticMinChunkSize,
                "semanticMaxChunkSize", semanticMaxChunkSize,
                "childChunkSize", childChunkSize,
                "childOverlap", childOverlap
        );
    }

    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }

    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
    }
}
