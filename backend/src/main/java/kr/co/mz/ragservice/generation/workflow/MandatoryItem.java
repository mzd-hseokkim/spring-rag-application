package kr.co.mz.ragservice.generation.workflow;

/**
 * RFP가 "제안서에 반드시 포함하라"고 명시한 작성 항목.
 * 기능/비기능 요구사항(SFR/NFR)과 별개로, 제안서 문서의 작성 의무 항목을 표현한다.
 *
 * 예: "제안기술의 우수성과 비교성을 기술", "타 PPP 사용자 사례", "기획부터 산출물까지의 반영 여부"
 */
public record MandatoryItem(
        String id,            // MAND-01, MAND-02 ...
        String title,         // 한 줄 제목
        String description,   // 무엇을 어떻게 다뤄야 하는지
        String sourceHint     // 추적용 (예: "RFP p.97")
) {}
