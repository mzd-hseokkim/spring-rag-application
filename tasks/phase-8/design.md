# Phase 8 — 사용자 인증 설계 (Design Document)

---

## 1. 현재 상태 분석

### 인증/인가 없음
- Spring Security 미도입, JWT 라이브러리 없음
- 모든 API 엔드포인트가 공개 상태
- `sessionId`는 클라이언트가 `crypto.randomUUID()`로 생성 (서버 검증 없음)

### 사용자 구분 없음
- `conversation` 테이블에 `user_id` 없음 → 모든 대화가 전역 조회됨
- `document` 테이블에 `user_id` 없음 → 모든 문서가 전역 조회됨
- `RateLimiter`가 `sessionId` 기반 → 세션을 새로 만들면 우회 가능

### 프론트엔드 라우팅 없음
- `react-router-dom` 미설치
- `App.tsx`가 단일 페이지 구조
- 로그인/회원가입 UI 없음

---

## 2. 아키텍처 결정

### 2-1. 인증 방식: JWT (Stateless)

| 항목 | 결정 |
|------|------|
| 인증 | Spring Security + JWT |
| 토큰 전달 | `Authorization: Bearer <token>` 헤더 |
| 액세스 토큰 TTL | 30분 |
| 리프레시 토큰 TTL | 7일 |
| 리프레시 토큰 저장 | DB (탈취 시 무효화 가능) |
| 비밀번호 해싱 | BCrypt |
| JWT 라이브러리 | `io.jsonwebtoken:jjwt` (0.12.x) |

### 2-2. 역할 (Role)

| 역할 | 권한 |
|------|------|
| `USER` | 채팅, 본인 대화 관리, 문서 업로드/조회 |
| `ADMIN` | USER 권한 + 모델 관리(CRUD), 전체 문서 관리 |

### 2-3. 엔드포인트 접근 제어

| 패턴 | 접근 |
|------|------|
| `POST /api/auth/**` | 누구나 (인증 불필요) |
| `GET /api/models`, `GET /api/models/{id}`, `POST /api/models/{id}/test` | 인증된 사용자 |
| `POST/PUT/DELETE /api/models/**`, `PATCH /api/models/**` | ADMIN만 |
| `GET /api/models/discover/**` | ADMIN만 |
| `/api/**` (나머지) | 인증된 사용자 |

---

## 3. DB 스키마 변경

### V10 — app_user 테이블 + refresh_token 테이블

```sql
CREATE TABLE app_user (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE refresh_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token       VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_token_user ON refresh_token (user_id);
CREATE INDEX idx_refresh_token_token ON refresh_token (token);
```

### V11 — conversation, document에 user_id 추가

```sql
ALTER TABLE conversation ADD COLUMN user_id UUID REFERENCES app_user(id);
ALTER TABLE document ADD COLUMN user_id UUID REFERENCES app_user(id);

CREATE INDEX idx_conversation_user ON conversation (user_id, updated_at DESC);
CREATE INDEX idx_document_user ON document (user_id);
```

> `user_id`는 NULL 허용 (기존 데이터 마이그레이션 호환). 신규 데이터는 NOT NULL 강제를 애플리케이션 레벨에서 처리.

---

## 4. Backend 설계

### 4-1. 패키지 구조

```
com.example.rag.auth/
├── AuthController.java          # 로그인/회원가입/토큰 갱신 API
├── AuthService.java             # 인증 비즈니스 로직
├── JwtTokenProvider.java        # JWT 생성/검증
├── JwtAuthenticationFilter.java # Spring Security 필터
├── SecurityConfig.java          # Spring Security 설정
├── AppUser.java                 # 사용자 JPA 엔티티
├── AppUserRepository.java       # 사용자 Repository
├── RefreshToken.java            # 리프레시 토큰 엔티티
├── RefreshTokenRepository.java  # 리프레시 토큰 Repository
└── UserRole.java                # USER, ADMIN enum
```

### 4-2. JwtTokenProvider

```java
public class JwtTokenProvider {
    // 설정값 (application.yml에서 주입)
    // app.jwt.secret: HS256 비밀키
    // app.jwt.access-token-expiry: 1800000 (30분)
    // app.jwt.refresh-token-expiry: 604800000 (7일)

    String generateAccessToken(UUID userId, String email, UserRole role)
    String generateRefreshToken()
    UUID getUserIdFromToken(String token)
    boolean validateToken(String token)
}
```

**JWT Payload (Claims):**
```json
{
  "sub": "user-uuid",
  "email": "user@example.com",
  "role": "USER",
  "iat": 1711000000,
  "exp": 1711001800
}
```

### 4-3. JwtAuthenticationFilter

- `OncePerRequestFilter` 상속
- `Authorization` 헤더에서 Bearer 토큰 추출
- `JwtTokenProvider.validateToken()` → `SecurityContextHolder`에 인증 정보 설정
- `/api/auth/**` 경로는 필터 스킵

### 4-4. SecurityConfig

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    SecurityFilterChain filterChain(HttpSecurity http) {
        // CSRF 비활성화 (JWT 사용)
        // CORS 허용 (프론트엔드 origin)
        // 세션 STATELESS
        // /api/auth/** → permitAll
        // /api/** → authenticated
        // JwtAuthenticationFilter → UsernamePasswordAuthenticationFilter 앞에 등록
    }
}
```

### 4-5. AuthController API

```
POST /api/auth/register
  Request:  { email, password, name }
  Response: { id, email, name, role }
  검증: 이메일 중복, 비밀번호 최소 8자

POST /api/auth/login
  Request:  { email, password }
  Response: { accessToken, refreshToken, user: { id, email, name, role } }

POST /api/auth/refresh
  Request:  { refreshToken }
  Response: { accessToken, refreshToken }
  동작: 기존 리프레시 토큰 삭제 + 새 토큰 쌍 발급 (Rotation)

GET /api/auth/me
  Header:   Authorization: Bearer <accessToken>
  Response: { id, email, name, role }
```

### 4-6. 기존 코드 변경

#### ConversationManagementService
```java
// 변경 전
public List<ConversationDto> listAll()
public Conversation getOrCreate(String sessionId, String modelId)

// 변경 후
public List<ConversationDto> listAllForUser(UUID userId)
public Conversation getOrCreate(String sessionId, String modelId, UUID userId)
```

#### ConversationRepository
```java
// 추가
@Query("SELECT c FROM Conversation c LEFT JOIN FETCH c.model WHERE c.user.id = :userId ORDER BY c.updatedAt DESC")
List<Conversation> findByUserIdOrderByUpdatedAtDesc(UUID userId);
```

#### Conversation 엔티티
```java
// 추가
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id")
private AppUser user;
```

#### DocumentService
```java
// 변경 전
public Document upload(MultipartFile file)
public List<Document> findAll()

// 변경 후
public Document upload(MultipartFile file, UUID userId)
public List<Document> findAllForUser(UUID userId)
```

#### Document 엔티티
```java
// 추가
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id")
private AppUser user;
```

#### RateLimiter
```java
// 변경 전
public void checkLimit(String sessionId)

// 변경 후
public void checkLimit(String userId)
// ChatController에서 JWT로부터 userId를 추출하여 전달
```

#### ChatController
```java
// 변경: 인증된 사용자 정보를 SecurityContext에서 추출
@PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter chat(@RequestBody ChatRequest request, Authentication auth) {
    UUID userId = UUID.fromString(auth.getName());
    rateLimiter.checkLimit(userId.toString());
    // ... 기존 로직
}
```

#### ConversationController
```java
@GetMapping
public List<ConversationDto> list(Authentication auth) {
    UUID userId = UUID.fromString(auth.getName());
    return managementService.listAllForUser(userId);
}
```

#### DocumentController
```java
@PostMapping
public Document upload(@RequestParam MultipartFile file, Authentication auth) {
    UUID userId = UUID.fromString(auth.getName());
    return documentService.upload(file, userId);
}
```

---

## 5. Frontend 설계

### 5-1. 의존성 추가

```json
{
  "react-router-dom": "^7.x"
}
```

### 5-2. 파일 구조

```
frontend/src/
├── api/
│   ├── client.ts              # 기존 (fetch wrapper에 auth 헤더 추가)
│   └── auth.ts                # 인증 API (login, register, refresh, me)
├── auth/
│   ├── AuthContext.tsx         # 인증 상태 Context + Provider
│   ├── useAuth.ts             # 인증 훅 (login, logout, user, isAuthenticated)
│   └── ProtectedRoute.tsx     # 미인증 시 로그인으로 리다이렉트
├── pages/
│   ├── LoginPage.tsx          # 로그인 페이지
│   ├── RegisterPage.tsx       # 회원가입 페이지
│   └── MainPage.tsx           # 기존 App.tsx 내용 이동
├── types/
│   └── index.ts               # User, AuthResponse 타입 추가
├── App.tsx                    # Router 설정
└── main.tsx
```

### 5-3. AuthContext

```typescript
interface AuthState {
  user: User | null;
  accessToken: string | null;
  loading: boolean;
}

// localStorage에 accessToken, refreshToken 저장
// API 호출 시 자동으로 Authorization 헤더 첨부
// 401 응답 시 refreshToken으로 자동 갱신 시도
// 갱신 실패 시 로그아웃 → 로그인 페이지로 리다이렉트
```

### 5-4. API 클라이언트 수정

```typescript
// fetch wrapper
async function apiFetch(url: string, options?: RequestInit): Promise<Response> {
  const token = localStorage.getItem('accessToken');
  const headers = {
    ...options?.headers,
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };

  let res = await fetch(url, { ...options, headers });

  // 401이면 토큰 갱신 후 재시도
  if (res.status === 401) {
    const refreshed = await tryRefreshToken();
    if (refreshed) {
      headers.Authorization = `Bearer ${localStorage.getItem('accessToken')}`;
      res = await fetch(url, { ...options, headers });
    }
  }

  return res;
}
```

### 5-5. 라우팅 구조

```tsx
<BrowserRouter>
  <Routes>
    <Route path="/login" element={<LoginPage />} />
    <Route path="/register" element={<RegisterPage />} />
    <Route path="/" element={
      <ProtectedRoute>
        <MainPage />
      </ProtectedRoute>
    } />
  </Routes>
</BrowserRouter>
```

### 5-6. UI 변경

#### 로그인 페이지
- 이메일 + 비밀번호 입력
- "회원가입" 링크
- 로그인 성공 → 메인 페이지로 이동

#### 회원가입 페이지
- 이름 + 이메일 + 비밀번호 + 비밀번호 확인
- 가입 성공 → 로그인 페이지로 이동

#### 사이드바 하단
- 사용자 이름 + 이메일 표시
- 로그아웃 버튼
- ADMIN이면 "모델" 탭 표시, USER면 숨김

---

## 6. 구현 순서

```
Step 1: Backend 기반
  ├── 의존성 추가 (Spring Security, jjwt)
  ├── Flyway V10 (app_user, refresh_token)
  ├── AppUser 엔티티 + Repository
  ├── JwtTokenProvider
  ├── AuthService
  └── AuthController

Step 2: Backend Security 적용
  ├── SecurityConfig
  ├── JwtAuthenticationFilter
  ├── application.yml JWT 설정 추가
  └── 동작 검증 (로그인/회원가입/토큰 갱신)

Step 3: Backend 데이터 격리
  ├── Flyway V11 (conversation, document에 user_id 추가)
  ├── Conversation, Document 엔티티 수정
  ├── Repository 쿼리 수정 (user 스코프)
  ├── Service 수정 (userId 파라미터 추가)
  ├── Controller 수정 (Authentication에서 userId 추출)
  └── RateLimiter userId 기반 전환

Step 4: Frontend 인증
  ├── react-router-dom 설치
  ├── AuthContext + useAuth 훅
  ├── API 클라이언트 수정 (auth 헤더, 401 처리)
  ├── LoginPage, RegisterPage
  ├── ProtectedRoute
  └── App.tsx 라우팅 적용

Step 5: Frontend UI 업데이트
  ├── 사이드바 하단 사용자 프로필
  ├── 모델 탭 ADMIN 제한
  └── 전체 동작 검증

Step 6: 빌드 및 통합 검증
```

---

## 7. 설정 값 (application.yml 추가분)

```yaml
app:
  jwt:
    secret: ${JWT_SECRET:default-dev-secret-key-min-32-chars-long!!}
    access-token-expiry: 1800000    # 30분
    refresh-token-expiry: 604800000 # 7일
```

---

## 8. 주의사항

- 기존 데이터(conversation, document)는 `user_id = NULL`로 유지. 첫 ADMIN 계정 생성 후 필요 시 수동 할당.
- `JWT_SECRET`은 환경변수로 주입. 기본값은 개발용.
- SSE 스트리밍 엔드포인트(`POST /api/chat`)도 JWT 필터를 통과해야 함. `Authorization` 헤더가 SSE 요청에도 포함되는지 프론트엔드에서 확인 필요.
- CORS: `SecurityConfig`에서 `http.cors()`로 Vite 개발 서버(localhost:5173) 허용.
