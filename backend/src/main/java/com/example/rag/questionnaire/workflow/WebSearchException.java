package com.example.rag.questionnaire.workflow;

public class WebSearchException extends RuntimeException {

    private final int statusCode;

    public WebSearchException(int statusCode, String responseBody) {
        super(toMessage(statusCode, responseBody));
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    private static String toMessage(int statusCode, String responseBody) {
        return switch (statusCode) {
            case 429 -> "웹 검색 API 요청 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.";
            case 432 -> "웹 검색 API 사용량 한도를 초과했습니다. 관리자에게 문의해 주세요.";
            case 401, 403 -> "웹 검색 API 인증에 실패했습니다. API 키를 확인해 주세요.";
            default -> "웹 검색 중 오류가 발생했습니다 (HTTP %d).".formatted(statusCode);
        };
    }
}
