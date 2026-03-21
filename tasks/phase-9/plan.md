# Phase 9 — 관리 기능 + 문서 관리 고도화

관리자 전용 관리 페이지(사용자/문서/대화 관리)를 추가하고, 문서에 태그/컬렉션 분류를 도입한다.

---

## Part A: 관리자 관리 페이지

### A-1. 사용자 관리

#### 기능
- 전체 사용자 목록 조회 (이메일, 이름, 역할, 가입일)
- 역할 변경 (USER ↔ ADMIN)
- 사용자 삭제 (연관 데이터 CASCADE)
- 사용자 검색/필터

#### API
```
GET    /api/admin/users                    # 사용자 목록 (페이징, 검색)
PATCH  /api/admin/users/{id}/role          # 역할 변경
DELETE /api/admin/users/{id}               # 사용자 삭제
```

### A-2. 문서 관리

#### 기능
- 전체 문서 목록 조회 (소유자, 공용 여부, 상태, 청크 수)
- 문서 공용 전환 (is_public 토글)
- 문서 삭제 (청크 포함 CASCADE)
- 상태/소유자별 필터

#### API
```
GET    /api/admin/documents                # 전체 문서 목록 (페이징, 필터)
PATCH  /api/admin/documents/{id}/public    # 공용 여부 변경
DELETE /api/admin/documents/{id}           # 문서 삭제
```

### A-3. 대화 관리

#### 기능
- 전체 대화 목록 조회 (소유자, 제목, 메시지 수, 최근 활동)
- 대화 상세 보기 (메시지 내용)
- 대화 삭제

#### API
```
GET    /api/admin/conversations                   # 전체 대화 목록 (페이징)
GET    /api/admin/conversations/{id}              # 대화 상세 (메시지 포함)
DELETE /api/admin/conversations/{id}              # 대화 삭제
```

### A-4. Frontend — 관리 페이지

#### 라우팅
- `/admin` — 관리 페이지 (ADMIN 전용)
- 사이드바 또는 헤더에 관리 페이지 진입 링크

#### 페이지 구성
- 탭 구조: 사용자 | 문서 | 대화
- 각 탭에 테이블 (정렬, 검색, 페이징)
- 인라인 액션 (역할 변경, 삭제, 공용 전환)

---

## Part B: 문서 태그/컬렉션

### B-1. 태그 시스템
- 문서에 다중 태그 부여 가능 (예: "기술", "논문", "회의록")
- 태그 자동 추천: 문서 업로드 후 LLM이 내용 기반으로 태그 제안 (선택)
- 태그 필터링: 사이드바 문서 목록에서 태그별 필터

### B-2. 컬렉션
- 문서를 논리적 그룹으로 묶는 컬렉션 생성
- 하나의 문서는 여러 컬렉션에 속할 수 있음
- 컬렉션 단위로 검색 범위 지정 가능

### B-3. DB 스키마

```sql
CREATE TABLE document_tag (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name    VARCHAR(100) NOT NULL UNIQUE
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
    user_id     UUID REFERENCES app_user(id),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE document_collection_mapping (
    document_id   UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    collection_id UUID NOT NULL REFERENCES document_collection(id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, collection_id)
);
```

### B-4. API

```
# 태그
GET    /api/tags                          # 태그 목록
POST   /api/tags                          # 태그 생성
DELETE /api/tags/{id}                     # 태그 삭제

# 문서 태그
PUT    /api/documents/{id}/tags           # 문서 태그 설정

# 컬렉션
GET    /api/collections                   # 컬렉션 목록
POST   /api/collections                   # 컬렉션 생성
PATCH  /api/collections/{id}              # 컬렉션 수정
DELETE /api/collections/{id}              # 컬렉션 삭제
PUT    /api/collections/{id}/documents    # 컬렉션 문서 할당
```

### B-5. 채팅 검색 범위 지정
- 채팅 UI에 검색 범위 선택기 추가
  - 전체 문서 (기본)
  - 특정 컬렉션
  - 특정 태그
- SearchService에 컬렉션/태그 기반 필터 추가

### B-6. Frontend
- 문서 카드에 태그 칩 표시
- 태그 필터 드롭다운
- 컬렉션 생성/편집/삭제
- 채팅 검색 범위 선택 UI

---

## 구현 순서

```
Part A (관리 기능) — 우선 구현
  Step 1: Backend Admin API (사용자/문서/대화 관리)
  Step 2: Frontend 관리 페이지 + 라우팅
  Step 3: 테이블 UI + 액션 연동

Part B (태그/컬렉션) — Part A 이후
  Step 4: DB 마이그레이션 (태그/컬렉션 테이블)
  Step 5: Backend 태그/컬렉션 CRUD
  Step 6: 검색 파이프라인 필터 확장
  Step 7: Frontend 태그/컬렉션 UI
  Step 8: 채팅 검색 범위 UI
```
