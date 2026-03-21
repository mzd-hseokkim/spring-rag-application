# Phase 11 — 관리자 대시보드 (Admin Dashboard)

사용량 통계, RAG 파이프라인 모니터링, 시스템 상태를 한눈에 볼 수 있는 대시보드를 구축한다.

---

## 1. 메트릭 수집

### 1-1. Spring Boot Actuator + Micrometer
- 의존성 추가: `spring-boot-starter-actuator`, `micrometer-registry-prometheus`
- 기본 메트릭: JVM, HTTP 요청, DB 커넥션 풀 등 자동 수집

### 1-2. 커스텀 메트릭
- `rag.chat.requests` — 채팅 요청 수 (Counter)
- `rag.chat.latency` — 응답 생성 시간 (Timer)
- `rag.chat.tokens` — 생성된 토큰 수 (Counter)
- `rag.search.latency` — 검색 파이프라인 시간 (Timer)
- `rag.search.results` — 검색 결과 수 (Distribution)
- `rag.agent.decisions` — 에이전트 결정 분포 (Counter, tag: action)
- `rag.document.uploads` — 문서 업로드 수 (Counter)
- `rag.document.chunks` — 청크 생성 수 (Counter)
- `rag.evaluation.scores` — 평가 점수 분포 (Distribution)

### 1-3. 파이프라인 트레이스 DB 저장
- 현재 `PipelineTracer`는 로그만 남김
- 트레이스를 DB에 저장하여 대시보드에서 조회 가능하도록 확장

```sql
CREATE TABLE pipeline_trace (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id        VARCHAR(20) NOT NULL,
    session_id      VARCHAR(100),
    query           TEXT NOT NULL,
    total_latency   INT NOT NULL,
    steps           JSONB NOT NULL,
    agent_action    VARCHAR(30),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
```

---

## 2. 대시보드 페이지

### 2-1. 개요 (Overview)
- 오늘/이번 주/이번 달 채팅 요청 수
- 평균 응답 시간
- 활성 대화 수
- 업로드된 문서 수 / 총 청크 수
- 에이전트 결정 분포 (DIRECT_ANSWER / SEARCH / CLARIFY 비율)

### 2-2. 파이프라인 모니터링
- 최근 요청 목록 (트레이스 ID, 질문 요약, 응답 시간, 에이전트 결정)
- 트레이스 상세: 각 스텝별 소요 시간 워터폴 차트
- 느린 요청 하이라이트 (임계값 초과)

### 2-3. 모델 사용량
- 모델별 요청 수, 토큰 사용량
- 모델별 평균 응답 시간 비교

### 2-4. 문서 현황
- 문서 상태 분포 (COMPLETED/FAILED/PROCESSING)
- 최근 업로드 이력
- 문서별 청크 수 분포

### 2-5. 평가 품질
- RAG 평가 점수 추이 (시계열)
- 점수 분포 히스토그램
- 낮은 점수 응답 목록 (개선 필요 항목)

---

## 3. Backend API

```
GET /api/admin/stats/overview              # 개요 통계
GET /api/admin/stats/chat?period=7d        # 채팅 통계 (기간별)
GET /api/admin/stats/models                # 모델별 사용량
GET /api/admin/stats/documents             # 문서 현황
GET /api/admin/traces?page=0&size=20       # 트레이스 목록
GET /api/admin/traces/{traceId}            # 트레이스 상세
GET /api/admin/evaluations?page=0&size=20  # 평가 결과 목록
```

---

## 4. Frontend

### 4-1. 라우팅
- `/dashboard` — 관리자 대시보드 (ADMIN 역할 필요)
- 사이드바에 "대시보드" 탭 추가 (ADMIN만 노출)

### 4-2. 차트 라이브러리
- Recharts 또는 Chart.js 활용
- 반응형 차트 (시계열, 바 차트, 파이 차트, 워터폴)

### 4-3. 실시간 갱신
- 대시보드 데이터 자동 폴링 (30초 간격)
- 또는 WebSocket으로 실시간 메트릭 푸시 (Phase 10 이후)

---

## 5. 시스템 상태

### 5-1. 헬스체크
- PostgreSQL 연결 상태
- Redis 연결 상태
- LLM 모델별 API 연결 상태 (간단한 ping 테스트)

### 5-2. 알림 (선택)
- 에러율 임계값 초과 시 대시보드 배너 알림
- 모델 API 연결 실패 시 경고 표시
