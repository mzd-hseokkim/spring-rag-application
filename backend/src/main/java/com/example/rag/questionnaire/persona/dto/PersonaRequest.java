package com.example.rag.questionnaire.persona.dto;

public record PersonaRequest(
        String name,
        String role,
        String focusAreas,
        String prompt
) {}
