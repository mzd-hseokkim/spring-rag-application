# Phase 9 — 문서 관리 고도화 (Document Management)

문서에 태그/폴더 분류를 추가하고, 채팅 시 검색 범위를 지정할 수 있도록 한다.

---

## 1. 문서 분류

### 1-1. 태그 시스템
- 문서에 다중 태그 부여 가능 (예: "기술", "논문", "회의록")
- 태그 자동 추천: 문서 업로드 후 LLM이 내용 기반으로 태그 제안
- 태그 필터링: 사이드바 문서 목록에서 태그별 필터

### 1-2. 폴더 (컬렉션)
- 문서를 논리적 그룹으로 묶는 컬렉션 생성
- 하나의 문서는 여러 컬렉션에 속할 수 있음
- 컬렉션 단위로 검색 범위 지정 가능

---

## 2. DB 스키마

```sql
-- 태그
CREATE TABLE document_tag (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name    VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE document_tag_mapping (
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    tag_id      UUID NOT NULL REFERENCES document_tag(id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, tag_id)
);

-- 컬렉션
CREATE TABLE document_collection (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE document_collection_mapping (
    document_id   UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    collection_id UUID NOT NULL REFERENCES document_collection(id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, collection_id)
);
```

---

## 3. 검색 범위 지정

### 3-1. 채팅 시 문서 범위 선택
- 채팅 UI에 "검색 범위" 선택기 추가
  - 전체 문서 (기본)
  - 특정 컬렉션
  - 특정 태그
  - 개별 문서 선택
- 선택된 범위를 API에 전달 → 벡터 검색 시 필터링

### 3-2. SearchService 수정
- 기존 `documentIds` 필터 외에 `collectionId`, `tagIds` 필터 추가
- WHERE 조건에 JOIN으로 필터링

---

## 4. API

```
# 태그
GET    /api/tags                          # 태그 목록
POST   /api/tags                          # 태그 생성
DELETE /api/tags/{id}                     # 태그 삭제

# 문서 태그
PUT    /api/documents/{id}/tags           # 문서 태그 설정 (전체 교체)

# 컬렉션
GET    /api/collections                   # 컬렉션 목록
POST   /api/collections                   # 컬렉션 생성
PATCH  /api/collections/{id}              # 컬렉션 수정
DELETE /api/collections/{id}              # 컬렉션 삭제

# 컬렉션 문서
PUT    /api/collections/{id}/documents    # 컬렉션에 문서 추가/제거
```

---

## 5. Frontend

### 5-1. 문서 탭 개선
- 문서 카드에 태그 칩 표시
- 태그 필터 드롭다운
- 문서 상세: 태그 편집, 컬렉션 할당

### 5-2. 컬렉션 관리
- 사이드바 문서 탭 상단에 컬렉션 목록 (트리뷰 또는 탭)
- 컬렉션 생성/편집/삭제

### 5-3. 채팅 검색 범위
- 채팅 입력 상단에 검색 범위 선택 UI
- 선택된 범위를 칩으로 표시
- "모든 문서" / 컬렉션명 / 태그명 / 개별 파일명 표시
