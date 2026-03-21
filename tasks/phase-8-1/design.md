# Phase 8-1 — 공용 문서 및 검색 범위 제어 설계 (Design Document)

---

## 1. 현재 상태 분석

### 문서 소유권
- `document` 테이블에 `user_id` 존재 (Phase 8에서 추가)
- 문서 목록 조회: `findByUserIdOrderByCreatedAtDesc(userId)` → 본인 문서만
- 공용/개인 구분 없음

### 검색 파이프라인
- `SearchAgent.decide()`: `documentRepository.findByStatus(COMPLETED)` → **전체 문서** 대상
- 에이전트가 관련 문서 ID를 선택 → `SearchService.search(query, documentIds)`
- 사용자 구분 없이 모든 완료된 문서가 검색 대상

### 문제점
- 다른 사용자의 개인 문서가 검색 결과에 노출될 수 있음
- 공용 문서 개념이 없어서 관리자가 모든 사용자를 위한 문서를 등록할 수 없음

---

## 2. 아키텍처 결정

### 2-1. 검색 범위 정책

| `includePublicDocs` | 검색 대상 |
|---------------------|----------|
| `true` (기본값) | 본인 문서 + 공용 문서 |
| `false` | 본인 문서만 |

> 항상 본인 문서는 포함. 공용 문서 포함 여부만 토글.

### 2-2. 공용 문서 등록 권한

| 역할 | 개인 문서 업로드 | 공용 문서 업로드 |
|------|----------------|----------------|
| USER | O | X |
| ADMIN | O | O (체크박스로 선택) |

---

## 3. DB 스키마 변경

### V12

```sql
ALTER TABLE document ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX idx_document_public ON document (is_public, status);
```

---

## 4. Backend 설계

### 4-1. Document 엔티티 변경

```java
// 추가
@Column(name = "is_public", nullable = false)
private boolean isPublic = false;

public boolean isPublic() { return isPublic; }
public void setPublic(boolean isPublic) { this.isPublic = isPublic; }
```

### 4-2. DocumentRepository 변경

```java
// 본인 문서 + 공용 문서 (문서 목록용)
@Query("SELECT d FROM Document d WHERE d.user.id = :userId OR d.isPublic = true ORDER BY d.createdAt DESC")
List<Document> findByUserIdOrIsPublicTrue(UUID userId);

// 검색 가능한 문서 목록 (에이전트용)
@Query("SELECT d FROM Document d WHERE d.status = :status AND (d.user.id = :userId OR d.isPublic = true)")
List<Document> findSearchableDocuments(DocumentStatus status, UUID userId);

// 본인 문서만 (공용 제외)
@Query("SELECT d FROM Document d WHERE d.status = :status AND d.user.id = :userId")
List<Document> findByStatusAndUserId(DocumentStatus status, UUID userId);
```

### 4-3. DocumentService 변경

```java
// 변경 전
public Document upload(MultipartFile file, UUID userId)

// 변경 후
public Document upload(MultipartFile file, UUID userId, boolean isPublic)

// 변경 전
public List<Document> findAllForUser(UUID userId)

// 변경 후 — 본인 문서 + 공용 문서 모두 반환
public List<Document> findAllForUser(UUID userId)
    → documentRepository.findByUserIdOrIsPublicTrue(userId)
```

### 4-4. DocumentController 변경

```java
// 변경 전
@PostMapping
public ResponseEntity<DocumentResponse> upload(@RequestParam("file") MultipartFile file,
                                                Authentication auth)

// 변경 후
@PostMapping
public ResponseEntity<DocumentResponse> upload(@RequestParam("file") MultipartFile file,
                                                @RequestParam(defaultValue = "false") boolean isPublic,
                                                Authentication auth)
// ADMIN이 아니면 isPublic 강제 false
```

### 4-5. DocumentResponse 변경

```java
// isPublic 필드 추가
public record DocumentResponse(
    UUID id, String filename, String contentType, long fileSize,
    String status, String errorMessage, int chunkCount,
    boolean isPublic,  // 추가
    String createdAt
)
```

### 4-6. ChatRequest 변경

```java
// 변경 전
record ChatRequest(String sessionId, String message, String modelId) {}

// 변경 후
record ChatRequest(String sessionId, String message, String modelId, Boolean includePublicDocs) {}
// null이면 기본값 true
```

### 4-7. ChatController → ChatService 전달

```java
// ChatController.chat()
boolean includePublic = request.includePublicDocs() == null || request.includePublicDocs();
ChatService.ChatResponse response = chatService.chat(
    request.sessionId(), request.message(), request.modelId(),
    userId, includePublic, step -> { ... });
```

### 4-8. ChatService.chat() 시그니처 변경

```java
// 변경 전
public ChatResponse chat(String sessionId, String message, String modelId,
                         UUID userId, Consumer<AgentStepEvent> stepCallback)

// 변경 후
public ChatResponse chat(String sessionId, String message, String modelId,
                         UUID userId, boolean includePublicDocs,
                         Consumer<AgentStepEvent> stepCallback)
```

### 4-9. SearchAgent.decide() 변경

```java
// 변경 전
public AgentDecision decide(String query) {
    List<Document> docs = documentRepository.findByStatus(DocumentStatus.COMPLETED);
    ...
}

// 변경 후
public AgentDecision decide(String query, UUID userId, boolean includePublicDocs) {
    List<Document> docs = includePublicDocs
        ? documentRepository.findSearchableDocuments(DocumentStatus.COMPLETED, userId)
        : documentRepository.findByStatusAndUserId(DocumentStatus.COMPLETED, userId);
    ...
}
```

---

## 5. Frontend 설계

### 5-1. 문서 업로드 UI 변경

**DocumentUpload 컴포넌트** — ADMIN일 때만 체크박스 추가:

```tsx
// props에 추가
interface Props {
  onUpload: (file: File, isPublic: boolean) => void;
  uploading: boolean;
  isAdmin: boolean;
}

// ADMIN일 때 체크박스 렌더
{isAdmin && (
  <label className="flex items-center gap-2 text-sm">
    <Checkbox checked={isPublic} onCheckedChange={setIsPublic} />
    공용 문서로 등록
  </label>
)}
```

### 5-2. 문서 목록 UI 변경

**DocumentList** — 공용 문서에 배지 표시:

```tsx
// 문서 카드에 추가
{doc.isPublic && (
  <Badge variant="secondary" className="text-xs">공용</Badge>
)}
```

### 5-3. 채팅 헤더에 토글 추가

**ChatView 헤더** — 모델 선택기 옆에 공용 문서 포함 토글:

```tsx
// 상태 추가
const [includePublicDocs, setIncludePublicDocs] = useState(true);

// 헤더에 토글
<div className="flex items-center gap-2">
  <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
    <Switch checked={includePublicDocs} onCheckedChange={setIncludePublicDocs} />
    공용 문서 포함
  </label>
  <ModelSelector ... />
  <Button ...>새 대화</Button>
</div>
```

### 5-4. useChat sendMessage 변경

```typescript
// 변경 전
const payload: Record<string, string> = {
  sessionId: sessionIdRef.current,
  message: content,
};

// 변경 후
const payload: Record<string, string | boolean> = {
  sessionId: sessionIdRef.current,
  message: content,
  includePublicDocs: includePublicDocs,
};
```

### 5-5. 타입 변경

```typescript
// Document 타입에 추가
export interface Document {
  ...
  isPublic: boolean;
}
```

---

## 6. 구현 순서

```
Step 1: DB + Entity
  ├── Flyway V12 (is_public 컬럼)
  ├── Document 엔티티 수정
  └── DocumentRepository 쿼리 추가

Step 2: Document API 수정
  ├── DocumentService (upload에 isPublic, 목록에 공용 포함)
  ├── DocumentController (isPublic 파라미터, ADMIN 검증)
  └── DocumentResponse (isPublic 필드)

Step 3: 검색 파이프라인 수정
  ├── SearchAgent.decide() — userId + includePublicDocs 필터링
  ├── ChatService.chat() — 파라미터 추가
  └── ChatController — ChatRequest 변경, 전달

Step 4: Frontend
  ├── Document 타입 수정
  ├── DocumentUpload (ADMIN 공용 체크박스)
  ├── DocumentList (공용 배지)
  ├── ChatView (공용 문서 포함 토글)
  └── useChat (includePublicDocs 전송)

Step 5: 빌드 및 검증
```

---

## 7. 주의사항

- 기존 문서는 `is_public = false`로 마이그레이션 → 기존 사용자 문서는 개인 문서로 유지
- `includePublicDocs` 기본값은 `true` → 사용자가 별도 조작 없이도 공용 문서가 검색에 포함됨
- ADMIN이 아닌 사용자가 `isPublic=true`로 요청하면 서버에서 강제 `false` 처리
- `DocumentUpload`에 `isAdmin` prop을 전달하기 위해 `MainPage`에서 `useAuth` 활용
