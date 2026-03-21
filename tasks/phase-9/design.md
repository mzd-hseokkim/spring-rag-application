# Phase 9 Part A — 관리자 관리 기능 설계 (Design Document)

---

## 1. 현재 사이드바 구조

```
┌─────────────────────┐
│  [대화] [문서] [모델] │  ← 탭 (모델은 ADMIN만)
├─────────────────────┤
│                     │
│  탭 콘텐츠 영역       │  ← 대화 목록 / 문서 목록 / 모델 관리
│                     │
├─────────────────────┤
│  사용자명   [로그아웃] │  ← 프로필
└─────────────────────┘
```

### 문제점
- 탭이 많아지면 가로 공간 부족 (대화/문서/모델 + 관리 탭들)
- 일반 기능과 관리 기능이 같은 레벨에 혼재
- 관리 페이지는 채팅 영역과 병행할 필요 없음 (별도 페이지가 적절)

---

## 2. 사이드바 설계

### 2-1. 구조: 모드 분리

사이드바를 **일반 모드**와 **관리 모드**로 분리한다.
ADMIN 사용자는 프로필 영역에서 모드를 전환할 수 있다.

```
일반 모드 (채팅)                    관리 모드 (ADMIN)
┌─────────────────────┐           ┌─────────────────────┐
│  [대화] [문서]       │           │  ← 채팅으로 돌아가기   │
├─────────────────────┤           ├─────────────────────┤
│                     │           │  ▸ 사용자 관리        │
│  탭 콘텐츠           │           │  ▸ 문서 관리          │
│                     │           │  ▸ 대화 관리          │
│                     │           │  ▸ 모델 관리          │
├─────────────────────┤           ├─────────────────────┤
│  사용자명            │           │  사용자명             │
│  [관리] [로그아웃]    │           │  [로그아웃]           │
└─────────────────────┘           └─────────────────────┘
```

### 2-2. 라우팅 구조

```
/                → MainPage (채팅 + 사이드바 일반 모드)
/admin           → AdminPage (관리 + 사이드바 관리 모드)
/admin/users     → 사용자 관리
/admin/documents → 문서 관리
/admin/conversations → 대화 관리
/admin/models    → 모델 관리 (기존 모델 탭 이동)
/login           → 로그인
/register        → 회원가입
```

### 2-3. 장점
- 일반 사이드바에서 "모델" 탭 제거 → 탭이 깔끔 (대화 / 문서)
- 관리 기능은 별도 레이아웃으로 분리 → 넓은 메인 영역을 테이블로 활용
- 관리 사이드바는 네비게이션 메뉴 방식 (탭이 아닌 목록)
- 채팅 ↔ 관리 전환이 명확

---

## 3. Frontend 설계

### 3-1. 파일 구조

```
frontend/src/
├── pages/
│   ├── MainPage.tsx             # 채팅 (기존, 모델 탭 제거)
│   ├── LoginPage.tsx            # 기존
│   ├── RegisterPage.tsx         # 기존
│   └── admin/
│       ├── AdminLayout.tsx      # 관리 레이아웃 (사이드바 + 콘텐츠)
│       ├── AdminUsersPage.tsx   # 사용자 관리
│       ├── AdminDocumentsPage.tsx # 문서 관리
│       ├── AdminConversationsPage.tsx # 대화 관리
│       └── AdminModelsPage.tsx  # 모델 관리 (기존 ModelManagement 재활용)
├── components/
│   └── admin/
│       ├── AdminSidebar.tsx     # 관리 사이드바 (네비게이션)
│       ├── UserTable.tsx        # 사용자 테이블
│       ├── AdminDocumentTable.tsx # 문서 테이블
│       └── AdminConversationTable.tsx # 대화 테이블
```

### 3-2. AdminLayout

```tsx
// 관리 페이지 공통 레이아웃
<div className="flex h-screen">
  <AdminSidebar />       {/* 왼쪽: 관리 네비게이션 */}
  <main className="flex-1 overflow-auto p-6">
    <Outlet />            {/* 오른쪽: 각 관리 페이지 */}
  </main>
</div>
```

### 3-3. AdminSidebar

```tsx
// 관리 사이드바 — 네비게이션 메뉴 방식
<aside className="w-64 bg-sidebar border-r flex flex-col">
  {/* 상단: 채팅으로 돌아가기 */}
  <Link to="/" className="...">
    <ArrowLeft /> 채팅으로 돌아가기
  </Link>

  <h2>관리</h2>

  {/* 네비게이션 */}
  <nav>
    <NavLink to="/admin/users">사용자 관리</NavLink>
    <NavLink to="/admin/documents">문서 관리</NavLink>
    <NavLink to="/admin/conversations">대화 관리</NavLink>
    <NavLink to="/admin/models">모델 관리</NavLink>
  </nav>

  {/* 하단: 프로필 */}
  <UserProfile />
</aside>
```

### 3-4. MainPage 사이드바 변경

```
변경 전: [대화] [문서] [모델(ADMIN)]  +  프로필 [로그아웃]
변경 후: [대화] [문서]               +  프로필 [관리(ADMIN)] [로그아웃]
```

- "모델" 탭 제거 → 관리 페이지로 이동
- ADMIN이면 프로필 영역에 "관리" 버튼 추가 → `/admin`으로 이동

### 3-5. ProtectedAdminRoute

```tsx
// ADMIN이 아니면 메인으로 리다이렉트
function ProtectedAdminRoute({ children }) {
  const { user } = useAuth();
  if (user?.role !== 'ADMIN') return <Navigate to="/" />;
  return children;
}
```

### 3-6. App.tsx 라우팅

```tsx
<Routes>
  <Route path="/login" element={<LoginPage />} />
  <Route path="/register" element={<RegisterPage />} />
  <Route path="/" element={<ProtectedRoute><MainPage /></ProtectedRoute>} />
  <Route path="/admin" element={<ProtectedAdminRoute><AdminLayout /></ProtectedAdminRoute>}>
    <Route index element={<Navigate to="users" replace />} />
    <Route path="users" element={<AdminUsersPage />} />
    <Route path="documents" element={<AdminDocumentsPage />} />
    <Route path="conversations" element={<AdminConversationsPage />} />
    <Route path="models" element={<AdminModelsPage />} />
  </Route>
</Routes>
```

---

## 4. Backend 설계

### 4-1. 패키지 구조

```
com.example.rag.admin/
├── AdminController.java         # 통합 관리 API
├── AdminService.java            # 관리 비즈니스 로직
└── dto/
    ├── AdminUserDto.java        # 사용자 정보 (비밀번호 제외)
    ├── AdminDocumentDto.java    # 문서 + 소유자 정보
    └── AdminConversationDto.java # 대화 + 소유자 + 메시지 수
```

### 4-2. AdminController

```java
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    // --- 사용자 관리 ---
    @GetMapping("/users")
    // 전체 사용자 목록 (검색: ?q=email/name, 페이징: ?page=0&size=20)

    @PatchMapping("/users/{id}/role")
    // 역할 변경 { "role": "ADMIN" }

    @DeleteMapping("/users/{id}")
    // 사용자 삭제 (본인 삭제 불가)

    // --- 문서 관리 ---
    @GetMapping("/documents")
    // 전체 문서 목록 (필터: ?status=COMPLETED&userId=xxx&isPublic=true)

    @PatchMapping("/documents/{id}/public")
    // 공용 여부 변경 { "isPublic": true }

    @DeleteMapping("/documents/{id}")
    // 문서 삭제

    // --- 대화 관리 ---
    @GetMapping("/conversations")
    // 전체 대화 목록 (필터: ?userId=xxx)

    @GetMapping("/conversations/{id}")
    // 대화 상세 (메시지 포함)

    @DeleteMapping("/conversations/{id}")
    // 대화 삭제
}
```

### 4-3. SecurityConfig 변경

```java
// 추가
.requestMatchers("/api/admin/**").hasRole("ADMIN")
```

### 4-4. 필요한 Repository 쿼리 추가

```java
// AppUserRepository
Page<AppUser> findByEmailContainingOrNameContaining(String email, String name, Pageable pageable);

// DocumentRepository — 관리용 (전체, 필터 가능)
Page<Document> findAll(Pageable pageable);

// ConversationRepository — 관리용
@Query("SELECT c FROM Conversation c LEFT JOIN FETCH c.model LEFT JOIN FETCH c.user ORDER BY c.updatedAt DESC")
Page<Conversation> findAllWithUserAndModel(Pageable pageable);
```

---

## 5. 관리 테이블 UI

### 5-1. 사용자 관리 테이블

| 이메일 | 이름 | 역할 | 가입일 | 액션 |
|--------|------|------|--------|------|
| admin@rag.com | 관리자 | `[ADMIN ▼]` | 2026-03-21 | 🗑 |
| user@rag.com | 사용자1 | `[USER ▼]` | 2026-03-21 | 🗑 |

- 역할: 드롭다운으로 즉시 변경
- 삭제: 확인 다이얼로그

### 5-2. 문서 관리 테이블

| 파일명 | 소유자 | 공용 | 상태 | 청크 | 등록일 | 액션 |
|--------|--------|------|------|------|--------|------|
| paper.pdf | admin@rag.com | `[✓]` | 완료 | 42 | 2026-03-21 | 🗑 |
| notes.md | user@rag.com | `[ ]` | 완료 | 12 | 2026-03-21 | 🗑 |

- 공용: 체크박스로 즉시 토글
- 상태/소유자별 필터 드롭다운

### 5-3. 대화 관리 테이블

| 제목 | 사용자 | 메시지 수 | 최근 활동 | 액션 |
|------|--------|----------|----------|------|
| Attention 메커니즘 | admin@rag.com | 8 | 3분 전 | 👁 🗑 |
| 기술 스택 문의 | user@rag.com | 4 | 1시간 전 | 👁 🗑 |

- 보기(👁): 대화 상세 모달 또는 확장
- 삭제: 확인 다이얼로그

---

## 6. 구현 순서

```
Step 1: Backend Admin API
  ├── AdminService + AdminController
  ├── DTO 정의
  ├── Repository 쿼리 추가
  └── SecurityConfig에 /api/admin/** 추가

Step 2: Frontend 라우팅 + 레이아웃
  ├── AdminLayout + AdminSidebar
  ├── ProtectedAdminRoute
  ├── App.tsx 라우팅 추가
  └── MainPage 사이드바 변경 (모델 탭 제거, 관리 버튼)

Step 3: 관리 페이지 구현
  ├── AdminUsersPage + UserTable
  ├── AdminDocumentsPage + AdminDocumentTable
  ├── AdminConversationsPage + AdminConversationTable
  └── AdminModelsPage (기존 ModelManagement 재활용)

Step 4: 빌드 및 검증
```
