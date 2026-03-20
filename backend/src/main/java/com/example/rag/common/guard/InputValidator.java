package com.example.rag.common.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InputValidator {

    private static final List<String> INJECTION_PATTERNS = List.of(
            "ignore previous instructions",
            "ignore all previous",
            "system:",
            "<|im_start|>",
            "<|im_end|>",
            "you are now",
            "act as if"
    );

    private final int maxInputLength;

    public InputValidator(@Value("${app.guard.max-input-length:2000}") int maxInputLength) {
        this.maxInputLength = maxInputLength;
    }

    public void validate(String message) {
        if (message == null || message.isBlank()) {
            throw new InputValidationException("메시지가 비어 있습니다.");
        }
        if (message.length() > maxInputLength) {
            throw new InputValidationException("메시지가 너무 깁니다. (최대 " + maxInputLength + "자)");
        }
        String lower = message.toLowerCase();
        for (String pattern : INJECTION_PATTERNS) {
            if (lower.contains(pattern)) {
                throw new InputValidationException("허용되지 않는 입력입니다.");
            }
        }
    }
}
