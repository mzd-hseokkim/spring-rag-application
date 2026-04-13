package kr.co.mz.ragservice.agent;

/**
 * Agent가 실행 중 각 단계를 프론트엔드에 알리기 위한 이벤트.
 * SSE로 전송되어 UI에 진행 상태를 표시한다.
 */
public record AgentStepEvent(
        String step,      // "decide", "search", "decompose", "sub_search", "synthesize"
        String message    // 사용자에게 보여줄 메시지
) {}
