# Phase 10 — WebSocket + Stop Generation 설계 (Design Document)

---

## 1. 현재 상태

### SSE 기반 채팅
- `POST /api/chat` → `SseEmitter` 반환 (300초 타임아웃)
- 10초 heartbeat로 연결 유지
- `CompletableFuture.runAsync()`로 비동기 실행
- 이벤트: `agent_step`, `token`, `sources`, `done`, `error`
- 응답 중단 불가 (Flux 구독 취소 메커니즘 없음)

### 프론트엔드
- `fetch` + `ReadableStream`으로 SSE 파싱
- 단방향 (서버 → 클라이언트)
- 스트리밍 중 "새 대화" 버튼 비활성화만 됨

---

## 2. 아키텍처 결정

### STOMP over WebSocket

| 항목 | 결정 |
|------|------|
| 프로토콜 | STOMP over WebSocket |
| 엔드포인트 | `/ws/chat` |
| 인증 | JWT를 query parameter로 전달 (`?token=xxx`) |
| 클라이언트 | `@stomp/stompjs` |
| SSE 유지 | 기존 SSE 엔드포인트 유지 (폴백용) |

### 메시지 라우팅

| 방향 | 경로 | 용도 |
|------|------|------|
| Client → Server | `/app/chat/send` | 채팅 메시지 전송 |
| Client → Server | `/app/chat/stop` | 생성 중단 요청 |
| Server → Client | `/user/queue/chat` | 응답 스트리밍 (토큰, 스텝, 소스, 에러) |

---

## 3. Backend 설계

### 3-1. 의존성

```kotlin
implementation("org.springframework.boot:spring-boot-starter-websocket")
```

### 3-2. WebSocketConfig

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat")
                .setAllowedOrigins("http://localhost:5173");
    }
}
```

### 3-3. WebSocket 인증

STOMP CONNECT 프레임에서 JWT 검증:

```java
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (CONNECT.equals(accessor.getCommand())) {
            // Authorization 헤더 또는 query param에서 토큰 추출
            String token = extractToken(accessor);
            // 검증 후 Principal 설정
            accessor.setUser(new StompPrincipal(userId));
        }
        return message;
    }
}
```

### 3-4. ChatWebSocketHandler

```java
@Controller
public class ChatWebSocketHandler {

    private final Map<String, Disposable> activeStreams = new ConcurrentHashMap<>();

    @MessageMapping("/chat/send")
    public void handleChat(ChatWsRequest request, Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        String sessionId = request.sessionId();

        // 기존 스트림 정리
        cancelIfActive(sessionId);

        // ChatService 호출
        ChatResponse response = chatService.chat(sessionId, message, ...);

        // Flux 구독 + 구독 저장
        Disposable subscription = response.tokens().subscribe(
            token -> send(userId, new WsMessage("token", token)),
            error -> send(userId, new WsMessage("error", error.getMessage())),
            () -> {
                send(userId, new WsMessage("sources", response.sources()));
                send(userId, new WsMessage("done", null));
                activeStreams.remove(sessionId);
            }
        );
        activeStreams.put(sessionId, subscription);
    }

    @MessageMapping("/chat/stop")
    public void handleStop(StopRequest request, Principal principal) {
        cancelIfActive(request.sessionId());
        send(principal, new WsMessage("stopped", null));
    }

    private void cancelIfActive(String sessionId) {
        Disposable sub = activeStreams.remove(sessionId);
        if (sub != null && !sub.isDisposed()) sub.dispose();
    }
}
```

### 3-5. SecurityConfig 변경

```java
// WebSocket 엔드포인트 허용
.requestMatchers("/ws/**").permitAll()
```

> WebSocket 인증은 STOMP CONNECT 단계에서 `ChannelInterceptor`가 처리.

---

## 4. Frontend 설계

### 4-1. 의존성

```json
"@stomp/stompjs": "^7.0.0"
```

### 4-2. useChat 변경

SSE fetch 대신 STOMP 클라이언트 사용:

```typescript
// STOMP 연결 (앱 시작 시)
const client = new Client({
    brokerURL: `ws://localhost:8080/ws/chat`,
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 5000,
});

// 메시지 전송
client.publish({
    destination: '/app/chat/send',
    body: JSON.stringify(payload),
});

// 응답 수신
client.subscribe('/user/queue/chat', (frame) => {
    const msg = JSON.parse(frame.body);
    // msg.type: agent_step | token | sources | done | stopped | error
});

// 중단
client.publish({
    destination: '/app/chat/stop',
    body: JSON.stringify({ sessionId }),
});
```

### 4-3. ChatInput 변경

스트리밍 중 전송 버튼 → 중단 버튼으로 전환:

```
[일반 상태]  [질문 입력...]  [↑ 전송]
[스트리밍]   [질문 입력...]  [■ 중단]
```

### 4-4. Vite 프록시 설정

```typescript
// vite.config.ts
server: {
    proxy: {
        '/ws': { target: 'ws://localhost:8080', ws: true },
    }
}
```

---

## 5. 메시지 형식

### Server → Client (WsMessage)

```json
{ "type": "agent_step", "step": "search", "message": "문서 검색 중..." }
{ "type": "token", "content": "검색" }
{ "type": "sources", "sources": [...] }
{ "type": "done" }
{ "type": "stopped" }
{ "type": "error", "message": "API key is not set" }
```

---

## 6. 구현 순서

```
Step 1: Backend WebSocket 설정
  ├── 의존성 추가
  ├── WebSocketConfig
  ├── WebSocketAuthInterceptor (JWT 검증)
  └── SecurityConfig 수정

Step 2: Backend 채팅 핸들러
  ├── ChatWebSocketHandler (send + stop)
  ├── DTO (ChatWsRequest, StopRequest, WsMessage)
  └── Flux 구독/취소 관리

Step 3: Frontend WebSocket 클라이언트
  ├── @stomp/stompjs 설치
  ├── useChat 리팩터 (SSE → WebSocket)
  ├── Vite proxy 설정
  └── 연결 상태 관리

Step 4: Frontend 중단 UI
  ├── ChatInput 중단 버튼
  ├── ChatView에 stop 전달
  └── 스트리밍 상태 UI

Step 5: 빌드 및 검증
```

---

## 7. 주의사항

- 기존 SSE 엔드포인트(`POST /api/chat`)는 삭제하지 않고 유지 (폴백)
- WebSocket 인증은 STOMP CONNECT 레벨에서 처리 (HTTP 필터와 분리)
- `activeStreams` Map은 서버 재시작 시 초기화됨 (문제 없음, 클라이언트가 재연결)
- `@stomp/stompjs`는 SockJS 없이 네이티브 WebSocket 사용 (모던 브라우저 대응)
