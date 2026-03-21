# Phase 8-1 — 공용 문서 및 검색 범위 제어

관리자가 공용 문서를 등록하고, 사용자가 RAG 검색 시 공용 문서 포함 여부를 선택할 수 있도록 한다.

---

## 1. 문서 공개 범위

### 1-1. 문서 유형
- **개인 문서**: 사용자 본인만 볼 수 있고, 본인의 RAG 검색에만 사용
- **공용 문서**: 관리자가 등록, 모든 사용자의 RAG 검색에 사용 가능

### 1-2. 등록 권한
- `USER`: 개인 문서만 업로드 가능
- `ADMIN`: 업로드 시 "공용 문서" 체크박스로 공용/개인 선택 가능

---

## 2. DB 스키마 변경

```sql
ALTER TABLE document ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT false;
```

---

## 3. Backend

### 3-1. Document 엔티티
- `isPublic` 필드 추가 (기본값 `false`)

### 3-2. DocumentRepository
```java
// 사용자의 개인 문서 + 공용 문서 전체
List<Document> findByUserIdOrIsPublicTrueOrderByCreatedAtDesc(UUID userId);

// 공용 문서만
List<Document> findByIsPublicTrueAndStatus(DocumentStatus status);
```

### 3-3. DocumentService
- `upload(file, userId, isPublic)` — 공용 여부 파라미터 추가
  - `isPublic = true`는 ADMIN만 허용 (컨트롤러에서 역할 검증)
- `findAllForUser(userId)` — 개인 문서 + 공용 문서 모두 반환

### 3-4. DocumentController
- `POST /api/documents` 요청에 `isPublic` 파라미터 추가 (기본값 `false`)
- ADMIN이 아닌 사용자가 `isPublic=true`로 보내면 무시 (강제 `false`)

### 3-5. 검색 파이프라인 수정

#### ChatRequest 변경
```java
record ChatRequest(String sessionId, String message, String modelId, boolean includePublicDocs) {}
```

#### ChatService.chat()
- `includePublicDocs` 파라미터 추가
- `SearchAgent.decide()`에 userId + includePublicDocs 전달

#### SearchAgent.decide()
- 현재: `documentRepository.findByStatus(COMPLETED)` → 전체 문서 대상
- 변경: userId 기반 필터링
  - `includePublicDocs = true`: 본인 문서(COMPLETED) + 공용 문서(COMPLETED)
  - `includePublicDocs = false`: 본인 문서(COMPLETED)만

---

## 4. Frontend

### 4-1. 문서 업로드 UI 변경
- ADMIN인 경우 업로드 폼에 "공용 문서로 등록" 체크박스 추가
- 체크 시 `isPublic=true`로 API 전송

### 4-2. 문서 목록 UI 변경
- 공용 문서에 배지 표시 (예: "공용" 라벨)
- 문서 목록에 개인/공용 구분 표시

### 4-3. 채팅 UI 변경
- 채팅 헤더 또는 입력 영역에 "공용 문서 포함" 토글/체크박스 추가
- 기본값: 체크됨 (공용 문서 포함)
- useChat의 sendMessage에 `includePublicDocs` 플래그 추가

---

## 5. 구현 순서

```
Step 1: DB 마이그레이션 (is_public 컬럼 추가)
Step 2: Document 엔티티 + Repository 수정
Step 3: DocumentService + DocumentController 수정 (업로드 시 공용 여부)
Step 4: SearchAgent 수정 (사용자별 + 공용 문서 필터링)
Step 5: ChatService + ChatController 수정 (includePublicDocs 전달)
Step 6: Frontend 문서 업로드 UI (공용 체크박스)
Step 7: Frontend 문서 목록 UI (공용 배지)
Step 8: Frontend 채팅 UI (공용 문서 포함 토글)
Step 9: 빌드 및 검증
```
