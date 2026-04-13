package kr.co.mz.ragservice.questionnaire.workflow;

public record Requirement(
        String id,
        String category,
        String item,
        String description,
        String importance,
        Integer weight
) {
    /**
     * 5-arg 생성자 호환 — 기존 호출부를 모두 한 번에 바꾸지 않기 위한 편의 생성자.
     * weight는 null로 둔다 (정량 배점 미확보 의미).
     */
    public Requirement(String id, String category, String item, String description, String importance) {
        this(id, category, item, description, importance, null);
    }
}
