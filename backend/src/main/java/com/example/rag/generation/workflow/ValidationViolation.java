package com.example.rag.generation.workflow;

/**
 * Validator가 발견한 outline 규칙 위반 항목.
 *
 * @param ruleName 위반된 규칙 이름 (예: "MIN_CHILDREN", "REQ_COVERAGE")
 * @param severity "error" 또는 "warning"
 * @param message  사람이 읽을 수 있는 설명
 * @param leafKey  관련 leaf의 key (특정 leaf가 없으면 null)
 */
public record ValidationViolation(
        String ruleName,
        String severity,
        String message,
        String leafKey
) {
    public static ValidationViolation error(String ruleName, String message, String leafKey) {
        return new ValidationViolation(ruleName, "error", message, leafKey);
    }

    public static ValidationViolation warning(String ruleName, String message, String leafKey) {
        return new ValidationViolation(ruleName, "warning", message, leafKey);
    }

    public boolean isError() {
        return "error".equals(severity);
    }
}
