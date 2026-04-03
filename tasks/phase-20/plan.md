# Phase 20 — 관리자 대시보드 보강 (생성 작업 통계 & 토큰 관리)

관리자 대시보드에 문서 생성/질문 생성 작업에 대한 통계·관리 기능과,
토큰 사용량의 목적별·비용 분석 기능을 추가한다.

---

## 배경

현재 관리자 대시보드는 채팅 관련 KPI와 토큰 일별 추이만 제공한다.
문서 생성(Generation)과 질문 생성(Questionnaire)은 사용자 페이지에서만 개별 조회 가능하고,
관리자 입장에서 전체 현황을 파악하거나 토큰 비용을 추적할 방법이 없다.

---

## 단계별 구현 계획

### Step 1. Overview 탭에 생성/질문 KPI 추가

**목표**: 대시보드 첫 화면에서 문서 생성·질문 생성 현황을 한눈에 확인

**Backend**:
- `DashboardController.getOverview()` 응답에 필드 추가:
  - `generationTotal` — 전체 문서 생성 건수
  - `generationToday` — 오늘 문서 생성 건수
  - `generationFailed` — 실패 건수
  - `questionnaireTotal` — 전체 질문 생성 건수
  - `questionnaireToday` — 오늘 질문 생성 건수
  - `questionnaireFailed` — 실패 건수
- `GenerationJobRepository`, `QuestionnaireJobRepository`에 count 쿼리 추가

**Frontend**:
- `OverviewTab.tsx`에 KPI 카드 2행 추가 (문서 생성 / 질문 생성)
- 기존 KPI 카드와 동일한 스타일 유지

**검증**: 대시보드 Overview에서 생성/질문 건수가 실제 DB와 일치하는지 확인

---

### Step 2. 생성 작업 일별 트렌드 차트

**목표**: 문서 생성·질문 생성의 일별 추이를 시각화

**Backend**:
- `GET /api/admin/dashboard/generation-trend?days=30` 엔드포인트 추가
  - 응답: `[{ date, generationCount, questionnaireCount }]`
- `GenerationJobRepository`에 일별 groupBy 쿼리 추가
- `QuestionnaireJobRepository`에 일별 groupBy 쿼리 추가

**Frontend**:
- Overview 탭 또는 별도 섹션에 라인 차트 추가
- 기존 chat-trend 차트와 동일한 7/30/90일 필터 적용

**검증**: 차트 데이터가 DB의 createdAt 기준 집계와 일치하는지 확인

---

### Step 3. 토큰 사용량 purpose별 필터·시각화 강화

**목표**: CHAT / GENERATION / QUESTIONNAIRE 등 목적별 토큰 소비를 명확히 구분

**Backend**:
- 기존 `GET /api/admin/dashboard/token-by-model` 응답에 purpose 집계가 이미 포함됨 → 확인
- 필요시 `GET /api/admin/dashboard/token-by-purpose?days=30` 엔드포인트 추가
  - 응답: `[{ purpose, inputTokens, outputTokens, requestCount }]`

**Frontend**:
- `TokenUsageTab.tsx`에 purpose별 도넛 차트 추가
- purpose별 필터 드롭다운 (전체 / CHAT / GENERATION / QUESTIONNAIRE)
- 필터 적용 시 일별 트렌드·유저별·모델별 데이터도 해당 purpose로 필터링

**검증**: purpose 필터 적용 전후 합계가 일치하는지 확인

---

### Step 4. 관리자용 생성 작업 관리 페이지

**목표**: 관리자가 전체 사용자의 문서 생성·질문 생성 작업을 조회·관리

**Backend**:
- `GET /api/admin/generations` — 전체 생성 작업 목록 (페이징, 상태 필터)
  - 응답: 작업 ID, 제목, 소유자, 상태, 생성일, 토큰 사용량
- `DELETE /api/admin/generations/{id}` — 생성 작업 삭제
- `GET /api/admin/questionnaires` — 전체 질문 생성 작업 목록 (페이징, 상태 필터)
- `DELETE /api/admin/questionnaires/{id}` — 질문 생성 작업 삭제

**Frontend**:
- `AdminGenerationsPage.tsx` 신규 페이지 생성
  - 탭 2개: 문서 생성 / 질문 생성
  - 상태 필터 (COMPLETE, FAILED, GENERATING 등)
  - 소유자별 검색
  - 작업 삭제 기능
- `AdminSidebar.tsx`에 "생성 관리" 메뉴 항목 추가

**검증**: 관리자 페이지에서 전체 작업 목록이 보이고, 삭제가 정상 동작하는지 확인

---

### Step 5. 토큰 비용 환산 기능

**목표**: 토큰 사용량을 비용(USD/KRW)으로 환산하여 표시

**Backend**:
- `model_pricing` 테이블 추가 (Flyway 마이그레이션)
  - `model_name`, `input_price_per_1k`, `output_price_per_1k`, `currency`, `updated_at`
- `GET /api/admin/settings/model-pricing` — 모델별 단가 조회
- `PUT /api/admin/settings/model-pricing` — 모델별 단가 설정
- `GET /api/admin/dashboard/token-cost?days=30` — 비용 환산 집계
  - 응답: `[{ date, model, purpose, inputTokens, outputTokens, estimatedCost }]`

**Frontend**:
- `TokenUsageTab.tsx`에 비용 컬럼 추가
  - 유저별 테이블: 예상 비용 컬럼
  - 모델별 테이블: 예상 비용 컬럼
  - 일별 트렌드: 비용 라인 오버레이 (선택적 표시)
- 관리자 설정(모델 관리 페이지 또는 별도)에 단가 설정 UI

**검증**: 수동 계산(토큰 수 × 단가)과 대시보드 비용이 일치하는지 확인

---

### Step 6. 생성 작업 파이프라인 트레이스

**목표**: 문서 생성·질문 생성의 단계별 소요 시간과 실패 지점을 추적

**Backend**:
- `generation_trace` 테이블 추가 (Flyway 마이그레이션)
  - `job_id`, `job_type` (GENERATION/QUESTIONNAIRE), `step_name`, `status`
  - `started_at`, `completed_at`, `duration_ms`, `error_message`
- 생성 워크플로우 각 단계에서 트레이스 기록:
  - Generation: OUTLINE → RESEARCH → SECTION_GENERATE → RENDER
  - Questionnaire: ANALYZE → GENERATE → RENDER
- `GET /api/admin/dashboard/generation-traces?page=0&size=20` 엔드포인트

**Frontend**:
- `PipelineTab.tsx`에 생성 트레이스 섹션 추가 (또는 별도 탭)
  - 작업별 단계 타임라인 시각화
  - 실패 단계 하이라이트 (빨간색)
  - 느린 단계 표시 (임계값 초과 시 노란색)

**검증**: 실제 생성 작업 실행 후 트레이스가 정확히 기록되는지 확인

---

## 의존성 관계

```
Step 1 (KPI)
    ↓
Step 2 (트렌드 차트) ← Step 1의 Repository 쿼리 재활용
    ↓
Step 3 (토큰 purpose 필터) ← 독립적, Step 1~2와 병행 가능
    ↓
Step 4 (생성 관리 페이지) ← Step 1 완료 후 진행 권장
    ↓
Step 5 (비용 환산) ← Step 3 완료 후 진행 (purpose별 데이터 필요)
    ↓
Step 6 (파이프라인 트레이스) ← 가장 큰 구조 변경, 마지막에 진행
```

## 비고

- Step 1~3은 기존 데이터와 API 구조를 활용하므로 비교적 빠르게 구현 가능
- Step 4는 기존 AdminDocumentsPage 패턴을 참고하여 구현
- Step 5는 새 테이블과 설정 UI가 필요하므로 중간 난이도
- Step 6는 기존 워크플로우 서비스에 트레이스 로직 삽입이 필요하므로 가장 큰 작업
