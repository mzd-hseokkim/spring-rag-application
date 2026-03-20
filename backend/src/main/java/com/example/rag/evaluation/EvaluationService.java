package com.example.rag.evaluation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

@Service
public class EvaluationService {

    private final FaithfulnessEvaluator faithfulnessEvaluator;
    private final RelevanceEvaluator relevanceEvaluator;
    private final double sampleRate;

    public EvaluationService(FaithfulnessEvaluator faithfulnessEvaluator,
                             RelevanceEvaluator relevanceEvaluator,
                             @Value("${app.evaluation.sample-rate:0.1}") double sampleRate) {
        this.faithfulnessEvaluator = faithfulnessEvaluator;
        this.relevanceEvaluator = relevanceEvaluator;
        this.sampleRate = sampleRate;
    }

    @Async("ingestionExecutor")
    public void evaluateIfSampled(String query, String context, String response) {
        if (ThreadLocalRandom.current().nextDouble() > sampleRate) {
            return; // 샘플링 대상 아님
        }
        faithfulnessEvaluator.evaluate(context, response);
        relevanceEvaluator.evaluate(query, response);
    }
}
