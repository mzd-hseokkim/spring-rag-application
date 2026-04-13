package kr.co.mz.ragservice.generation.workflow;

import java.util.List;

/**
 * OutlineValidator의 검증 결과.
 * passed=true이면 에러 위반이 없음을 의미. (warning은 passed=true 상태에서도 있을 수 있음)
 */
public record ValidationResult(
        boolean passed,
        List<ValidationViolation> violations
) {
    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult of(List<ValidationViolation> violations) {
        boolean hasError = violations.stream().anyMatch(ValidationViolation::isError);
        return new ValidationResult(!hasError, violations);
    }

    public List<ValidationViolation> errors() {
        return violations.stream().filter(ValidationViolation::isError).toList();
    }

    public List<ValidationViolation> warnings() {
        return violations.stream().filter(v -> !v.isError()).toList();
    }
}
