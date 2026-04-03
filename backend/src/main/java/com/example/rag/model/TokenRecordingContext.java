package com.example.rag.model;

import java.util.UUID;

/**
 * 토큰 기록에 필요한 컨텍스트를 ThreadLocal로 전달한다.
 * @Async 스레드에서 SecurityContext가 없으므로, 워크플로우 시작 시 설정한다.
 */
public final class TokenRecordingContext {

    private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();

    private TokenRecordingContext() {}

    public static void setUserId(UUID userId) {
        USER_ID.set(userId);
    }

    public static UUID getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
