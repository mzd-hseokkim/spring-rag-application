package com.example.rag.model;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

/**
 * Qwen3-Embedding 등 Matryoshka(MRL) 학습 모델의 출력을 앞쪽 N차원으로 잘라내고
 * L2 정규화하여 pgvector 컬럼 차원과 맞춘다.
 */
public class TruncatingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final int targetDim;

    public TruncatingEmbeddingModel(EmbeddingModel delegate, int targetDim) {
        this.delegate = delegate;
        this.targetDim = targetDim;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        EmbeddingResponse response = delegate.call(request);
        List<Embedding> truncated = response.getResults().stream()
                .map(e -> new Embedding(truncateAndNormalize(e.getOutput()), e.getIndex(), e.getMetadata()))
                .toList();
        return new EmbeddingResponse(truncated, response.getMetadata());
    }

    @Override
    public float[] embed(Document document) {
        return truncateAndNormalize(delegate.embed(document));
    }

    @Override
    public int dimensions() {
        return targetDim;
    }

    private float[] truncateAndNormalize(float[] src) {
        int n = Math.min(src.length, targetDim);
        float[] out = new float[n];
        System.arraycopy(src, 0, out, 0, n);
        double sumSq = 0.0;
        for (float v : out) {
            sumSq += v * v;
        }
        if (sumSq == 0.0) {
            return out;
        }
        float norm = (float) Math.sqrt(sumSq);
        for (int i = 0; i < out.length; i++) {
            out[i] /= norm;
        }
        return out;
    }
}
