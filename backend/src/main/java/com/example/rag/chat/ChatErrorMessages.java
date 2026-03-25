package com.example.rag.chat;

import com.example.rag.questionnaire.workflow.WebSearchException;

/**
 * 채팅 파이프라인에서 발생하는 예외를 사용자 친화적 메시지로 변환한다.
 */
final class ChatErrorMessages {

    private ChatErrorMessages() {}

    static String toUserMessage(Throwable e) {
        if (e == null) {
            return "알 수 없는 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";
        }

        // 이미 사용자 친화적 메시지를 가진 예외
        if (e instanceof WebSearchException) {
            return e.getMessage();
        }

        String msg = e.getMessage();
        if (msg == null) msg = "";

        // LLM API 관련 에러
        if (containsAny(msg, "429", "Too Many Requests", "rate limit", "Rate limit")) {
            return "AI 서비스 요청 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.";
        }
        if (containsAny(msg, "401", "403", "Unauthorized", "Forbidden", "authentication", "Invalid API")) {
            return "AI 서비스 인증에 실패했습니다. 관리자에게 문의해 주세요.";
        }
        if (containsAny(msg, "timeout", "Timeout", "timed out", "Read timed out", "connect timed out")) {
            return "AI 서비스 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.";
        }
        if (containsAny(msg, "500", "502", "503", "504", "Internal Server Error", "Service Unavailable", "Bad Gateway")) {
            return "AI 서비스에 일시적인 장애가 발생했습니다. 잠시 후 다시 시도해 주세요.";
        }
        if (containsAny(msg, "Connection refused", "Connection reset", "UnknownHostException", "No route to host")) {
            return "AI 서비스에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.";
        }

        // 컨텍스트 길이 초과
        if (containsAny(msg, "context length", "token limit", "max_tokens", "too long")) {
            return "질문 또는 문서가 너무 길어 처리할 수 없습니다. 질문을 짧게 줄여 주세요.";
        }

        // 기본 메시지
        return "요청을 처리하는 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}
