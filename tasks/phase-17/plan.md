# Phase 17 — 감사 로그 + API Rate Limiting 고도화

시스템 보안을 강화하고, API 사용량을 세밀하게 제어한다.

---

## 1. 감사 로그 (Audit Log)

### 1-1. 기록 대상
- **인증**: 로그인 성공/실패, 회원가입, 로그아웃
- **데이터 변경**: 문서 업로드/삭제, 대화 삭제, 사용자 역할 변경
- **관리자 액션**: 모델 등록/삭제, 평가 실행, 공용 문서 전환
- **보안 이벤트**: rate limit 초과, 토큰 만료, 접근 거부

### 1-2. DB 스키마

```sql
CREATE TABLE audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES app_user(id),
    user_email  VARCHAR(255),
    action      VARCHAR(50) NOT NULL,
    resource    VARCHAR(50),
    resource_id VARCHAR(100),
    detail      JSONB,
    ip_address  VARCHAR(50),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_created ON audit_log (created_at DESC);
CREATE INDEX idx_audit_log_user ON audit_log (user_id);
CREATE INDEX idx_audit_log_action ON audit_log (action);
```

### 1-3. 구현
- `AuditService.log(userId, action, resource, detail)` 메서드
- AOP 또는 이벤트 기반으로 각 포인트에서 자동 기록
- 관리자 대시보드에서 감사 로그 조회/검색/필터

## 2. API Rate Limiting 고도화

### 2-1. 현재 문제
- 단순 카운터 기반 (분당 N회)
- 모든 사용자에게 동일 한도
- 채팅 API만 적용

### 2-2. 개선

#### 다중 레벨 제한
- **사용자별**: 분당/시간당/일당 한도
- **역할별**: USER는 분당 10회, ADMIN은 분당 30회
- **API별**: 채팅 / 문서 업로드 / 평가 실행 각각 별도 한도

#### 슬라이딩 윈도우
- 현재 고정 윈도우 → 슬라이딩 윈도우로 변경
- Redis ZSET + 타임스탬프 기반 정밀 제어

#### 설정
```yaml
app:
  rate-limit:
    chat:
      user: 10/min, 100/hour
      admin: 30/min, 300/hour
    upload:
      user: 5/min
    eval:
      admin: 2/hour
```

### 2-3. 응답 헤더
- `X-RateLimit-Limit`: 한도
- `X-RateLimit-Remaining`: 남은 횟수
- `X-RateLimit-Reset`: 리셋 시간
- 429 응답 시 `Retry-After` 헤더

## 3. 관리자 UI
- 감사 로그 조회 페이지 (필터: 사용자, 액션, 기간)
- Rate limit 현황 대시보드 (사용자별 사용량)
