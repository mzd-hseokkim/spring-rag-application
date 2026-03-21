# Phase 9 Part B — 문서 태그/컬렉션 설계 (Design Document)

---

## 1. 현재 검색 파이프라인 흐름

```
ChatView (includePublicDocs toggle)
→ useChat.sendMessage(content, modelId, includePublicDocs)
→ ChatRequest { sessionId, message, modelId, includePublicDocs }
→ ChatService.chat() → SearchAgent.decide(query, userId, includePublicDocs)
→ documentRepository.findSearchableDocuments(COMPLETED, userId)
→ LLM이 관련 문서 선택 → targetDocumentIds
→ SearchService.search(query, documentIds)
→ VectorSearchService + KeywordSearchService (SQL IN 필터)
```

핵심: `documentIds` 리스트로 필터링하는 구조가 이미 존재 → 태그/컬렉션은 **documentIds를 필터링하는 단계**를 추가하면 됨.

---

## 2. DB 스키마

### V13

```sql
CREATE TABLE document_tag (
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE document_tag_mapping (
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    tag_id      UUID NOT NULL REFERENCES document_tag(id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, tag_id)
);

CREATE TABLE document_collection (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    user_id     UUID REFERENCES app_user(id) ON DELETE CASCADE,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE document_collection_mapping (
    document_id   UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    collection_id UUID NOT NULL REFERENCES document_collection(id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, collection_id)
);
```

---

## 3. Backend 설계

### 3-1. 엔티티

```java
// DocumentTag — 글로벌 태그 (모든 사용자 공유)
@Entity
public class DocumentTag {
    UUID id;
    String name;  // UNIQUE
}

// DocumentCollection — 사용자별 컬렉션
@Entity
public class DocumentCollection {
    UUID id;
    String name;
    String description;
    AppUser user;  // 소유자
    LocalDateTime createdAt;
}
```

Document 엔티티에 ManyToMany 관계 추가:
```java
@ManyToMany
@JoinTable(name = "document_tag_mapping", ...)
private Set<DocumentTag> tags;

@ManyToMany
@JoinTable(name = "document_collection_mapping", ...)
private Set<DocumentCollection> collections;
```

### 3-2. API

```
# 태그 CRUD
GET    /api/tags                          # 전체 태그 목록
POST   /api/tags                          # 태그 생성 { name }
DELETE /api/tags/{id}                     # 태그 삭제

# 문서-태그 연결
PUT    /api/documents/{id}/tags           # 문서 태그 설정 { tagIds: [...] }

# 컬렉션 CRUD (사용자별)
GET    /api/collections                   # 내 컬렉션 목록
POST   /api/collections                   # 컬렉션 생성 { name, description }
PATCH  /api/collections/{id}              # 컬렉션 수정
DELETE /api/collections/{id}              # 컬렉션 삭제

# 컬렉션-문서 연결
PUT    /api/collections/{id}/documents    # 컬렉션에 문서 할당 { documentIds: [...] }
GET    /api/collections/{id}/documents    # 컬렉션의 문서 목록
```

### 3-3. 검색 파이프라인 확장

#### ChatRequest 변경
```java
record ChatRequest(
    String sessionId,
    String message,
    String modelId,
    Boolean includePublicDocs,
    List<String> tagIds,          // 추가: 선택된 태그 ID
    String collectionId           // 추가: 선택된 컬렉션 ID
) {}
```

#### SearchAgent.decide() 변경
```java
public AgentDecision decide(String query, UUID userId, boolean includePublicDocs,
                            List<UUID> tagIds, UUID collectionId) {
    List<Document> docs = ...;

    // 태그 필터: 선택된 태그가 있으면 해당 태그를 가진 문서만
    if (tagIds != null && !tagIds.isEmpty()) {
        docs = docs.stream()
            .filter(d -> d.getTags().stream().anyMatch(t -> tagIds.contains(t.getId())))
            .toList();
    }

    // 컬렉션 필터: 선택된 컬렉션이 있으면 해당 컬렉션의 문서만
    if (collectionId != null) {
        docs = docs.stream()
            .filter(d -> d.getCollections().stream().anyMatch(c -> c.getId().equals(collectionId)))
            .toList();
    }

    // LLM 에이전트 결정 (기존 로직)
    ...
}
```

---

## 4. Frontend 설계

### 4-1. 타입

```typescript
interface DocumentTag { id: string; name: string; }
interface DocumentCollection { id: string; name: string; description?: string; }

// Document 타입에 추가
interface Document {
  ...
  tags: DocumentTag[];
}
```

### 4-2. 문서 탭 개선

문서 목록 상단에 태그 필터 + 컬렉션 선택:
```
┌─────────────────────────┐
│ 컬렉션: [전체 ▼]         │
│ 태그: [기술][논문] ×      │
├─────────────────────────┤
│ 📄 paper.pdf  [기술][논문]│
│ 📄 notes.md   [회의록]   │
└─────────────────────────┘
```

- 문서 항목에 태그 칩 표시
- 태그 클릭 → 해당 태그 필터 적용
- 문서 우클릭/메뉴 → 태그 편집, 컬렉션 할당

### 4-3. 채팅 검색 범위 선택

ChatView 헤더에 검색 범위 선택기:
```
┌──────────────────────────────────────────────────┐
│ 채팅   [검색범위: 전체 ▼] [공용문서 ○] [모델 ▼] [새대화] │
└──────────────────────────────────────────────────┘
```

검색범위 드롭다운:
- 전체 문서 (기본)
- 컬렉션별: "프로젝트A", "논문 모음" ...
- 태그별: "기술", "논문" ...

선택 시 `sendMessage`에 `tagIds` 또는 `collectionId` 전달.

### 4-4. 컬렉션 관리

사이드바 문서 탭에서 컬렉션 생성/편집/삭제:
- 상단에 컬렉션 드롭다운 (전체 / 컬렉션명)
- 컬렉션 선택 시 해당 컬렉션의 문서만 표시
- "+" 버튼으로 새 컬렉션 생성

---

## 5. 구현 순서

```
Step 1: DB + Entity + Repository
  ├── Flyway V13 (태그/컬렉션 테이블)
  ├── DocumentTag, DocumentCollection 엔티티
  ├── Document 엔티티에 ManyToMany 추가
  └── Repository 생성

Step 2: Backend CRUD API
  ├── TagController + TagService
  ├── CollectionController + CollectionService
  └── DocumentController에 태그 설정 엔드포인트 추가

Step 3: 검색 파이프라인 태그/컬렉션 필터
  ├── ChatRequest 확장 (tagIds, collectionId)
  ├── ChatService → SearchAgent 전달
  └── SearchAgent에서 태그/컬렉션 필터링

Step 4: Frontend
  ├── 태그/컬렉션 타입 + API 클라이언트
  ├── 문서 목록에 태그 표시 + 태그 편집
  ├── 컬렉션 관리 UI (드롭다운 + CRUD)
  ├── ChatView 검색 범위 드롭다운
  └── useChat에 tagIds/collectionId 전달

Step 5: 빌드 및 검증
```
