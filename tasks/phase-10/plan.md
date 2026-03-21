# Phase 10 — 스트리밍 성능 개선 (WebSocket + Stop Generation)

SSE → WebSocket 전환으로 양방향 통신을 지원하고, 응답 중단(Stop) 기능을 추가한다.

---

## 1. WebSocket 전환

### 1-1. 왜 WebSocket인가
- 현재 SSE: 단방향 (서버 → 클라이언트), 연결당 하나의 요청
- WebSocket: 양방향, 하나의 연결로 다중 메시지 교환
- 채팅 중단(Stop) 기능 구현 가능
- 타이핑 인디케이터, 실시간 알림 등 확장 가능

### 1-2. 구현
- Spring WebSocket + STOMP 프로토콜
- 엔드포인트: `/ws/chat`
- 토픽 구조:
  - `/topic/chat/{sessionId}` — 응답 스트리밍 (토큰, 에이전트 스텝, 소스, 에러)
  - `/app/chat/send` — 메시지 전송
  - `/app/chat/stop` — 생성 중단

### 1-3. SSE 폴백
- WebSocket 연결 실패 시 기존 SSE로 자동 폴백
- 클라이언트에서 연결 방식 자동 감지

---

## 2. 응답 중단 (Stop Generation)

### 2-1. Backend
- `ChatService`에서 Flux 구독 취소 메커니즘 추가
- sessionId별 활성 구독(Disposable) 관리
- 중단 요청 시 구독 해제 → 스트림 종료

### 2-2. Frontend
- 스트리밍 중 "중단" 버튼 표시 (새 대화 버튼 위치)
- 중단 시 WebSocket으로 stop 메시지 전송
- 현재까지 받은 토큰을 그대로 유지하고 스트리밍 종료

---

## 3. 연결 관리

### 3-1. 재연결
- WebSocket 끊김 시 자동 재연결 (지수 백오프)
- 재연결 시 세션 ID 유지
- 연결 상태 표시 (연결됨/재연결 중/연결 끊김)

---

## 구현 순서

```
Step 1: Backend WebSocket 설정 + STOMP 메시지 핸들러
Step 2: Backend 응답 중단 메커니즘
Step 3: Frontend WebSocket 클라이언트 (SSE 대체)
Step 4: Frontend 응답 중단 UI
Step 5: 빌드 및 검증
```
