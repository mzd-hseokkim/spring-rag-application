package kr.co.mz.ragservice.generation.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SectionPlan(
        String key,
        String heading,
        String purpose,
        List<String> keyPoints,
        int estimatedLength
) {}
