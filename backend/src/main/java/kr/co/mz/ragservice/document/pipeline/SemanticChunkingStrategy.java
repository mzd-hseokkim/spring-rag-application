package kr.co.mz.ragservice.document.pipeline;

import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class SemanticChunkingStrategy implements ChunkingStrategy {

    private static final Pattern SENTENCE_SPLIT =
            Pattern.compile("(?<=[.!?。])(\\s+)(?=[A-Z가-힣\"'(\\[])|(?<=\\n\\n)");

    private final Supplier<EmbeddingModel> embeddingModelSupplier;
    private final int bufferSize;
    private final double breakpointPercentile;
    private final int minChunkSize;
    private final int maxChunkSize;
    private final FixedSizeChunkingStrategy fallbackStrategy;

    public SemanticChunkingStrategy(Supplier<EmbeddingModel> embeddingModelSupplier,
                                    int bufferSize,
                                    double breakpointPercentile,
                                    int minChunkSize,
                                    int maxChunkSize) {
        this.embeddingModelSupplier = embeddingModelSupplier;
        this.bufferSize = bufferSize;
        this.breakpointPercentile = breakpointPercentile;
        this.minChunkSize = minChunkSize;
        this.maxChunkSize = maxChunkSize;
        this.fallbackStrategy = new FixedSizeChunkingStrategy(500, 100);
    }

    @Override
    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 1. 문장 분리
        List<String> sentences = splitSentences(text);
        if (sentences.size() < 5) {
            // 짧은 문서: 통계적 분할 불안정 → 전체를 하나의 청크로
            return List.of(text.trim());
        }

        // 2. Buffer 적용 (전후 문장 묶기)
        List<String> buffered = buildBufferedSentences(sentences);

        // 3. 배치 임베딩
        float[][] embeddings = embedBatch(buffered);

        // 4. 인접 코사인 거리 계산
        double[] distances = computeDistances(embeddings);

        // 5. Breakpoint 탐지
        List<Integer> breakpoints = findBreakpoints(distances);

        // 6. 청크 구성
        List<String> rawChunks = formChunks(sentences, breakpoints);

        // 7. 최소/최대 크기 제약 적용
        return enforceMinMax(rawChunks);
    }

    private List<String> splitSentences(String text) {
        String[] parts = SENTENCE_SPLIT.split(text);
        return Arrays.stream(parts)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<String> buildBufferedSentences(List<String> sentences) {
        List<String> buffered = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = Math.max(0, i - bufferSize); j <= Math.min(sentences.size() - 1, i + bufferSize); j++) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(sentences.get(j));
            }
            buffered.add(sb.toString());
        }
        return buffered;
    }

    /**
     * 임베딩 모델의 최대 토큰 한도를 초과하지 않도록 텍스트를 잘라서 임베딩한다.
     * 대부분의 임베딩 모델은 8192 토큰 한도를 가지며, 한국어/영어 혼합 기준
     * 약 1문자 ≈ 1~2토큰이므로 안전하게 문자 수 기준으로 제한한다.
     */
    private static final int MAX_EMBED_CHARS = 6000;

    private float[][] embedBatch(List<String> texts) {
        EmbeddingModel embeddingModel = embeddingModelSupplier.get();
        float[][] result = new float[texts.size()][];
        // 배치 크기 제한 (512개씩)
        int batchSize = 512;
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            for (int j = 0; j < batch.size(); j++) {
                String text = batch.get(j);
                if (text.length() > MAX_EMBED_CHARS) {
                    text = text.substring(0, MAX_EMBED_CHARS);
                }
                result[i + j] = embeddingModel.embed(text);
            }
        }
        return result;
    }

    private double[] computeDistances(float[][] embeddings) {
        double[] distances = new double[embeddings.length - 1];
        for (int i = 0; i < distances.length; i++) {
            distances[i] = 1.0 - cosine(embeddings[i], embeddings[i + 1]);
        }
        return distances;
    }

    private List<Integer> findBreakpoints(double[] distances) {
        // percentile 계산
        double[] sorted = Arrays.copyOf(distances, distances.length);
        Arrays.sort(sorted);
        int percentileIdx = (int) Math.ceil(breakpointPercentile / 100.0 * sorted.length) - 1;
        percentileIdx = Math.max(0, Math.min(percentileIdx, sorted.length - 1));
        double threshold = sorted[percentileIdx];

        List<Integer> breakpoints = new ArrayList<>();
        for (int i = 0; i < distances.length; i++) {
            if (distances[i] >= threshold) {
                breakpoints.add(i + 1); // 문장 인덱스 기준 (i+1번째 문장 앞에서 분할)
            }
        }
        return breakpoints;
    }

    private List<String> formChunks(List<String> sentences, List<Integer> breakpoints) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        for (int bp : breakpoints) {
            String chunk = joinSentences(sentences, start, bp);
            if (!chunk.isEmpty()) chunks.add(chunk);
            start = bp;
        }
        // 마지막 청크
        String last = joinSentences(sentences, start, sentences.size());
        if (!last.isEmpty()) chunks.add(last);
        return chunks;
    }

    private String joinSentences(List<String> sentences, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(sentences.get(i));
        }
        return sb.toString().trim();
    }

    private List<String> enforceMinMax(List<String> chunks) {
        // 최소 크기: 작은 청크를 다음 청크에 병합
        List<String> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String chunk : chunks) {
            if (buffer.isEmpty()) {
                buffer.append(chunk);
            } else {
                buffer.append(" ").append(chunk);
            }
            if (buffer.length() >= minChunkSize) {
                merged.add(buffer.toString());
                buffer = new StringBuilder();
            }
        }
        if (!buffer.isEmpty()) {
            if (merged.isEmpty()) {
                merged.add(buffer.toString());
            } else {
                // 마지막에 남은 작은 조각은 이전 청크에 병합
                String last = merged.removeLast() + " " + buffer;
                merged.add(last);
            }
        }

        // 최대 크기: 초과 시 고정 크기로 2차 분할
        List<String> result = new ArrayList<>();
        for (String chunk : merged) {
            if (chunk.length() > maxChunkSize) {
                result.addAll(fallbackStrategy.chunk(chunk));
            } else {
                result.add(chunk);
            }
        }
        return result;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
