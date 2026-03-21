# Phase 10 — 스트리밍 성능 개선 (Streaming & Caching)

SSE → WebSocket 전환을 통해 양방향 통신을 지원하고, 응답 캐싱으로 반복 질문 성능을 개선한다.

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
- 스트리밍 중 "중단" 버튼 표시
- 중단 시 WebSocket으로 stop 메시지 전송
- 현재까지 받은 토큰을 그대로 유지하고 스트리밍 종료

---

## 3. 응답 캐싱

### 3-1. 시맨틱 캐시
- 동일하거나 유사한 질문에 대해 이전 응답을 캐시에서 반환
- 질문 임베딩 기반 유사도 비교 (코사인 유사도 > 0.95)
- 캐시 히트 시 RAG 파이프라인 건너뛰기 → 빠른 응답

### 3-2. DB 스키마

```sql
CREATE TABLE response_cache (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_hash     VARCHAR(64) NOT NULL,
    query_text     TEXT NOT NULL,
    query_embedding vector(1536),
    response_text  TEXT NOT NULL,
    sources        JSONB,
    hit_count      INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    expires_at     TIMESTAMP NOT NULL
);

CREATE INDEX idx_response_cache_embedding ON response_cache
    USING ivfflat (query_embedding vector_cosine_ops);
```

### 3-3. 캐시 정책
- TTL: 24시간 (문서 업로드/삭제 시 관련 캐시 무효화)
- 문서 변경 시 캐시 전체 클리어 또는 해당 문서 참조 캐시만 무효화

---

## 4. 연결 관리

### 4-1. 재연결
- WebSocket 끊김 시 자동 재연결 (지수 백오프)
- 재연결 시 세션 ID 유지
- 연결 상태 표시 (연결됨/재연결 중/연결 끊김)

### 4-2. 다중 탭 지원
- 같은 세션을 여러 탭에서 열어도 충돌 없이 동작
- BroadcastChannel API로 탭 간 상태 동기화
