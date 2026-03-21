# Phase 11 — 관리자 대시보드 설계 (Design Document)

---

## 1. 현재 상태

### 데이터 수집
- **파이프라인 트레이스**: `TraceContext`에 스텝별 소요 시간/메타데이터 기록 → 로그에만 출력 (DB 미저장)
- **RAG 평가**: `EvaluationService`에서 faithfulness/relevance 점수 계산 → 로그에만 출력 (DB 미저장)
- **토큰 사용량**: 미추적
- **관리 API**: 사용자/문서/대화 CRUD만 존재, 통계 API 없음

### 누락 항목
- 트레이스 DB 영속화
- 토큰 사용량 추적 (사용자별, 모델별)
- 통계/집계 API
- 차트 라이브러리 (recharts 미설치)

---

## 2. DB 스키마

### V14

```sql
-- 파이프라인 트레이스
CREATE TABLE pipeline_trace (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id        VARCHAR(20) NOT NULL,
    session_id      VARCHAR(100),
    user_id         UUID REFERENCES app_user(id),
    query           TEXT NOT NULL,
    agent_action    VARCHAR(30),
    total_latency   INT NOT NULL,
    steps           JSONB NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_pipeline_trace_created ON pipeline_trace (created_at DESC);
CREATE INDEX idx_pipeline_trace_user ON pipeline_trace (user_id);

-- 토큰 사용량
CREATE TABLE token_usage (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES app_user(id),
    model_id        UUID REFERENCES llm_model(id),
    model_name      VARCHAR(200) NOT NULL,
    purpose         VARCHAR(30) NOT NULL,
    input_tokens    INT NOT NULL DEFAULT 0,
    output_tokens   INT NOT NULL DEFAULT 0,
    session_id      VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_token_usage_user ON token_usage (user_id, created_at DESC);
CREATE INDEX idx_token_usage_model ON token_usage (model_name, created_at DESC);
CREATE INDEX idx_token_usage_created ON token_usage (created_at DESC);

-- RAG 평가 결과
CREATE TABLE evaluation_result (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id        VARCHAR(20),
    user_id         UUID REFERENCES app_user(id),
    query           TEXT NOT NULL,
    faithfulness    DOUBLE PRECISION,
    relevance       DOUBLE PRECISION,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_evaluation_created ON evaluation_result (created_at DESC);
```

---

## 3. 대시보드 구성

### 3-1. 개요 탭 (Overview)

KPI 카드 (상단 4개):
```
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ 오늘 채팅     │ │ 활성 사용자   │ │ 총 문서      │ │ 평균 응답시간  │
│    127       │ │     12       │ │    45        │ │   2.3초      │
│ ▲ 23% vs 어제│ │              │ │ 공용 8 / 개인 37│              │
└──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
```

하단:
- 일별 채팅 요청 수 (시계열 라인 차트, 최근 30일)
- 에이전트 결정 분포 (파이 차트: DIRECT_ANSWER / SEARCH / CLARIFY)

### 3-2. 토큰 사용량 탭 (Token Usage)

```
┌─────────────────────────────┐ ┌─────────────────────┐
│ 일별 토큰 사용량 추이 (Line) │ │ 모델별 토큰 비율     │
│ ━━ 입력 토큰                │ │     (Pie Chart)     │
│ ━━ 출력 토큰                │ │                     │
└─────────────────────────────┘ └─────────────────────┘

┌───────────────────────────────────────────────────────┐
│ 사용자별 토큰 사용량 (테이블, 정렬 가능)                  │
│ 사용자      │ 입력 토큰 │ 출력 토큰 │ 합계    │ 요청 수  │
│ admin@...  │ 12,340   │ 45,670  │ 58,010 │   127  │
│ user1@...  │  8,200   │ 23,100  │ 31,300 │    56  │
└───────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────┐
│ 모델별 토큰 사용량 (테이블)                              │
│ 모델          │ 용도  │ 입력 토큰 │ 출력 토큰 │ 요청 수  │
│ Claude Haiku │ CHAT  │ 15,000  │ 52,000  │   183  │
│ Claude Haiku │ QUERY │  8,400  │  2,100  │   183  │
└───────────────────────────────────────────────────────┘
```

기간 필터: 오늘 / 7일 / 30일 / 전체

### 3-3. 파이프라인 탭 (Pipeline)

```
┌───────────────────────────────────────────────────────┐
│ 최근 요청 (테이블)                                      │
│ 시간       │ 사용자  │ 질문 요약    │ 결정    │ 시간  │ 토큰 │
│ 10:23:15  │ admin  │ Attention... │ SEARCH │ 2.3s │ 450  │
│ 10:22:01  │ user1  │ 기술 스택... │ DIRECT │ 0.8s │ 120  │
└───────────────────────────────────────────────────────┘

┌─────────────────────────────┐ ┌─────────────────────┐
│ 스텝별 평균 소요 시간 (Bar)   │ │ 응답 시간 분포       │
│ compress ████ 120ms         │ │    (Histogram)      │
│ decide   ██████ 250ms      │ │                     │
│ search   ████████ 450ms    │ │                     │
│ generate █████████████ 1.5s│ │                     │
└─────────────────────────────┘ └─────────────────────┘
```

### 3-4. 문서 현황 탭 (Documents)

```
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ 문서 상태 분포 │ │ 공용/개인 비율 │ │ 사용자별 문서 │
│ (Pie Chart)  │ │ (Pie Chart)  │ │ (Bar Chart)  │
└──────────────┘ └──────────────┘ └──────────────┘
```

### 3-5. 시스템 상태 탭 (System)

```
┌───────────────────────────────────────┐
│ 시스템 상태                             │
│ ● PostgreSQL    연결됨   응답 12ms     │
│ ● Redis         연결됨   응답 3ms      │
│ ● Claude API    연결됨                 │
│ ● Ollama        연결됨   모델 3개 활성  │
└───────────────────────────────────────┘
```

---

## 4. Backend 설계

### 4-1. 토큰 사용량 수집

Spring AI는 ChatResponse에 usage 정보(input/output tokens)를 포함합니다.
현재 `chatClient().prompt().stream().content()`로 토큰만 스트리밍하고 usage는 버리고 있습니다.

**수집 방식**: ChatService에서 응답 완료 시 토큰 사용량을 DB에 저장.

단, streaming `.content()`는 usage를 제공하지 않으므로, **토큰 수를 문자열 길이 기반으로 추정** (입력: 프롬프트 길이 / 4, 출력: 응답 길이 / 4) 하거나, non-streaming 호출 (QUERY, RERANK 용도)에서는 정확한 usage를 수집합니다.

```java
@Entity
@Table(name = "token_usage")
public class TokenUsage {
    UUID id;
    AppUser user;
    LlmModel model;
    String modelName;
    String purpose;     // CHAT, QUERY, RERANK, EVALUATION
    int inputTokens;
    int outputTokens;
    String sessionId;
    LocalDateTime createdAt;
}
```

### 4-2. 트레이스 DB 저장

`PipelineTracer.logTrace()` 수정 → 로그 + DB 저장:

```java
@Entity
@Table(name = "pipeline_trace")
public class PipelineTraceEntity {
    UUID id;
    String traceId;
    String sessionId;
    AppUser user;
    String query;
    String agentAction;
    int totalLatency;
    String steps;       // JSONB (JSON 문자열)
    LocalDateTime createdAt;
}
```

### 4-3. 평가 결과 DB 저장

`EvaluationService` 수정 → 로그 + DB 저장:

```java
@Entity
@Table(name = "evaluation_result")
public class EvaluationResultEntity {
    UUID id;
    String traceId;
    AppUser user;
    String query;
    Double faithfulness;
    Double relevance;
    LocalDateTime createdAt;
}
```

### 4-4. 통계 API

```
GET /api/admin/dashboard/overview          # KPI 카드 데이터
GET /api/admin/dashboard/chat-trend?days=30 # 일별 채팅 수 추이
GET /api/admin/dashboard/agent-distribution # 에이전트 결정 분포
GET /api/admin/dashboard/token-trend?days=30 # 일별 토큰 사용량 추이
GET /api/admin/dashboard/token-by-user?days=30 # 사용자별 토큰 사용량
GET /api/admin/dashboard/token-by-model?days=30 # 모델별 토큰 사용량
GET /api/admin/dashboard/pipeline-traces?page=0&size=20 # 트레이스 목록
GET /api/admin/dashboard/pipeline-stats    # 스텝별 평균 소요 시간
GET /api/admin/dashboard/document-stats    # 문서 통계
GET /api/admin/dashboard/system-health     # 시스템 상태
```

### 4-5. 통계 쿼리 예시

```sql
-- 일별 채팅 수
SELECT DATE(created_at) as day, COUNT(*) as count
FROM pipeline_trace
WHERE created_at >= NOW() - INTERVAL '30 days'
GROUP BY DATE(created_at) ORDER BY day;

-- 사용자별 토큰 합산
SELECT u.email, u.name,
       SUM(t.input_tokens) as total_input,
       SUM(t.output_tokens) as total_output,
       COUNT(*) as request_count
FROM token_usage t JOIN app_user u ON t.user_id = u.id
WHERE t.created_at >= NOW() - INTERVAL '30 days'
GROUP BY u.id, u.email, u.name
ORDER BY total_output DESC;

-- 모델별 토큰 합산
SELECT model_name, purpose,
       SUM(input_tokens) as total_input,
       SUM(output_tokens) as total_output,
       COUNT(*) as request_count
FROM token_usage
WHERE created_at >= NOW() - INTERVAL '30 days'
GROUP BY model_name, purpose
ORDER BY total_output DESC;
```

---

## 5. Frontend 설계

### 5-1. 의존성

```json
"recharts": "^2.x"
```

### 5-2. 라우팅

관리 사이드바에 "대시보드" 항목 추가:
```
/admin/dashboard          → AdminDashboardPage
/admin/dashboard/tokens   → 토큰 사용량 탭
/admin/dashboard/pipeline → 파이프라인 탭
```

단일 페이지에 탭으로 구성 (라우팅 분리 안 함):
```
/admin/dashboard  →  [개요] [토큰] [파이프라인] [문서] [시스템]
```

### 5-3. 파일 구조

```
frontend/src/pages/admin/
├── AdminDashboardPage.tsx        # 대시보드 메인 (탭 컨테이너)
├── dashboard/
│   ├── OverviewTab.tsx           # 개요 KPI + 차트
│   ├── TokenUsageTab.tsx         # 토큰 사용량 차트 + 테이블
│   ├── PipelineTab.tsx           # 파이프라인 모니터링
│   ├── DocumentStatsTab.tsx      # 문서 현황
│   └── SystemHealthTab.tsx       # 시스템 상태
```

### 5-4. 차트 컴포넌트

| 차트 | 라이브러리 | 용도 |
|------|----------|------|
| LineChart | recharts | 일별 추이 (채팅 수, 토큰) |
| BarChart | recharts | 스텝별 소요 시간, 사용자별 토큰 |
| PieChart | recharts | 에이전트 분포, 모델별 비율, 문서 상태 |

### 5-5. 기간 필터

모든 통계 탭에 공통 기간 선택기:
```
[오늘] [7일] [30일] [전체]
```

---

## 6. 구현 순서

```
Step 1: DB 마이그레이션 + 엔티티
  ├── Flyway V14 (pipeline_trace, token_usage, evaluation_result)
  ├── 엔티티 + Repository 생성

Step 2: 데이터 수집 연동
  ├── PipelineTracer → DB 저장
  ├── ChatService/ChatWebSocketHandler → 토큰 사용량 저장
  ├── EvaluationService → 평가 결과 DB 저장

Step 3: 통계 API
  ├── DashboardService (집계 쿼리)
  ├── DashboardController (통계 엔드포인트)
  └── 시스템 헬스체크 API

Step 4: Frontend 대시보드
  ├── recharts 설치
  ├── AdminDashboardPage + 탭 컴포넌트
  ├── AdminSidebar에 대시보드 추가
  └── 차트 + 테이블 구현

Step 5: 빌드 및 검증
```

---

## 7. 주의사항

- 토큰 수 추정: 스트리밍 응답에서 정확한 token count는 불가 → 문자열 길이 / 4로 근사
- non-streaming 호출(QUERY, RERANK)에서는 Spring AI의 `ChatResponse.getMetadata().getUsage()` 활용 가능
- `pipeline_trace`와 `token_usage`는 데이터가 빠르게 쌓이므로, 향후 TTL 기반 정리 또는 집계 테이블 도입 고려
- 대시보드 폴링 주기: 30초 (실시간성과 서버 부하 균형)
