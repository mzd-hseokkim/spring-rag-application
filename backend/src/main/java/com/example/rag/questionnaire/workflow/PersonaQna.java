package com.example.rag.questionnaire.workflow;

import java.util.List;

public record PersonaQna(
        String personaName,
        String personaRole,
        List<QuestionAnswer> questions
) {}
