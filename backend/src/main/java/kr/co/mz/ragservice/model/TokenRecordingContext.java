package kr.co.mz.ragservice.model;

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

    /**
     * 현재 ThreadLocal의 userId를 캡처하여 작업에 전파한다.
     * CompletableFuture.runAsync 등 ForkJoinPool 워커로 작업을 넘길 때 사용한다.
     * 워커 스레드는 부모의 ThreadLocal을 상속하지 않으므로 명시적으로 전달해야 한다.
     */
    public static Runnable wrap(Runnable task) {
        UUID captured = USER_ID.get();
        return () -> {
            try {
                USER_ID.set(captured);
                task.run();
            } finally {
                USER_ID.remove();
            }
        };
    }
}
