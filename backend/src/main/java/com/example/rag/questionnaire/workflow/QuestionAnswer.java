package com.example.rag.questionnaire.workflow;

import java.util.List;

public record QuestionAnswer(
        String question,
        String answer,
        String difficulty,
        String category,
        List<String> sources
) {}
