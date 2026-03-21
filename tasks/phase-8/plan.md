# Phase 8 — 사용자 인증 (Authentication & Multi-User)

사용자 인증을 도입하고, 대화/문서를 사용자별로 분리한다.

---

## 1. 인증 방식

### 1-1. Spring Security + JWT
- 회원가입 / 로그인 (이메일 + 비밀번호)
- JWT 액세스 토큰 + 리프레시 토큰
- 액세스 토큰: 30분, 리프레시 토큰: 7일
- 토큰은 httpOnly 쿠키 또는 Authorization 헤더로 전달

### 1-2. 역할 (Role)
- `USER`: 기본 사용자 (채팅, 문서 업로드)
- `ADMIN`: 관리자 (모델 관리, 전체 문서 관리, 대시보드)

---

## 2. DB 스키마

### app_user 테이블

```sql
CREATE TABLE app_user (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
```

### 기존 테이블 변경

```sql
-- conversation 테이블에 user_id 추가
ALTER TABLE conversation ADD COLUMN user_id UUID REFERENCES app_user(id);

-- document 테이블에 user_id 추가 (NULL = 공유 문서)
ALTER TABLE document ADD COLUMN user_id UUID REFERENCES app_user(id);
```

---

## 3. Backend

### 3-1. 의존성 추가
- `spring-boot-starter-security`
- `jjwt` (io.jsonwebtoken)

### 3-2. 보안 설정
- `/api/auth/**` — 인증 없이 접근 허용
- `/api/**` — JWT 인증 필요
- CORS 설정 (프론트엔드 origin 허용)
- CSRF 비활성화 (JWT 사용 시)

### 3-3. API

```
POST /api/auth/register           # 회원가입
POST /api/auth/login              # 로그인 → JWT 발급
POST /api/auth/refresh            # 토큰 갱신
GET  /api/auth/me                 # 현재 사용자 정보
```

### 3-4. 데이터 격리
- 대화 목록: 현재 사용자의 대화만 조회
- 문서 목록: 본인 업로드 문서 + 공유 문서
- RateLimiter: sessionId → userId 기반으로 전환

---

## 4. Frontend

### 4-1. 로그인/회원가입 페이지
- 이메일 + 비밀번호 폼
- 인증 상태에 따라 라우팅 (미인증 → 로그인, 인증 → 채팅)

### 4-2. 인증 상태 관리
- JWT를 localStorage 또는 httpOnly 쿠키에 저장
- API 호출 시 Authorization 헤더 자동 첨부 (fetch wrapper)
- 토큰 만료 시 자동 갱신 또는 로그인 리다이렉트

### 4-3. UI 변경
- 사이드바 하단에 사용자 프로필 + 로그아웃 버튼
- ADMIN 역할일 때만 "모델" 탭 표시
