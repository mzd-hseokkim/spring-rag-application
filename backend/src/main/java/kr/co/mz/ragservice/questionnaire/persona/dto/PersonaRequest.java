package kr.co.mz.ragservice.questionnaire.persona.dto;

public record PersonaRequest(
        String name,
        String role,
        String focusAreas,
        String prompt
) {}
