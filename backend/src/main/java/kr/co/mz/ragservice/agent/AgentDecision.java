package kr.co.mz.ragservice.agent;

import java.util.List;
import java.util.UUID;

public record AgentDecision(
        AgentAction action,
        List<UUID> targetDocumentIds  // SEARCH 시 대상 문서 (빈 리스트면 전체 검색)
) {
    public static AgentDecision directAnswer() {
        return new AgentDecision(AgentAction.DIRECT_ANSWER, List.of());
    }

    public static AgentDecision clarify() {
        return new AgentDecision(AgentAction.CLARIFY, List.of());
    }

    public static AgentDecision search(List<UUID> documentIds) {
        return new AgentDecision(AgentAction.SEARCH, documentIds);
    }
}
