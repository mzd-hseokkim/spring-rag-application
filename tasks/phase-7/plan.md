# Phase 7 — 대화 이력 관리 (Conversation History)

대화를 저장/관리하고, 이전 대화를 다시 불러올 수 있도록 한다.

---

## 1. 대화 목록 관리

### 1-1. 대화 저장
- 새 대화 시작 시 `conversation` 레코드 생성
- 세션 ID와 매핑

### 1-2. 대화 제목 자동 생성
- 첫 번째 사용자 메시지 + AI 응답 후 LLM으로 대화 제목 자동 생성
- 짧고 요약적인 제목 (예: "Attention 메커니즘 설명", "기술 스택 문의")
- 제목 생성은 비동기 (응답 완료 후)

### 1-3. 대화 목록 UI
- 사이드바에 대화 목록 탭 추가 (문서 / 모델 / 대화)
- 최근 순 정렬
- 대화 선택 시 이력 로드
- 대화 삭제
- 제목 수동 편집 가능

---

## 2. DB 스키마

### conversation 테이블

```sql
CREATE TABLE conversation (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  VARCHAR(100) NOT NULL UNIQUE,
    title       VARCHAR(500),
    model_id    UUID REFERENCES llm_model(id),
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
```

---

## 3. 이력 영속화

### 3-1. Redis → DB 전환 (선택)
- 현재: Redis에 대화 이력 저장 (TTL 1시간)
- 변경 옵션: DB에 영속 저장, Redis는 캐시로만 사용
- 또는: Redis 유지하되 TTL 연장 + conversation 테이블로 메타데이터 관리

### 3-2. 대화 이력 로드
- 대화 선택 → sessionId로 Redis에서 이력 조회
- Redis에 없으면 (만료된 경우) 빈 대화로 표시

---

## 4. API

```
GET    /api/conversations                    # 대화 목록 (최근 순)
POST   /api/conversations                    # 새 대화 생성
GET    /api/conversations/{id}               # 대화 상세 (메시지 포함)
PATCH  /api/conversations/{id}/title         # 제목 수정
DELETE /api/conversations/{id}               # 대화 삭제
```

---

## 5. 프론트엔드

### 5-1. 사이드바 대화 목록
- 대화 제목, 생성 시간, 사용 모델 표시
- 현재 활성 대화 하이라이트
- 스와이프 또는 우클릭으로 삭제

### 5-2. 새 대화 흐름
- "새 대화" 버튼 → conversation 생성 → 빈 채팅 화면
- 첫 응답 후 제목 자동 생성 → 사이드바 목록 갱신
