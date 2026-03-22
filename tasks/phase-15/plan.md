# Phase 15 — 멀티턴 RAG 개선 (최대 3단계)

현재 RAG 파이프라인의 멀티스텝 처리를 간소화하여 응답 속도를 개선한다.

---

## 1. 현재 문제

### 과도한 단계
- compress → decide → decompose → (multi-step search * N) → generate
- 간단한 질문도 모든 단계를 거침 → 불필요한 지연
- 멀티스텝 분해 시 서브쿼리 수에 비례하여 검색 횟수 증가

## 2. 개선: 최대 3단계로 제한

### 2-1. 새로운 파이프라인 흐름
```
Stage 1: 분석 (Analysis)
  - 쿼리 압축 + 에이전트 결정을 하나의 LLM 호출로 통합
  - 결과: DIRECT_ANSWER | CLARIFY | SEARCH(단일/멀티)

Stage 2: 검색 (Retrieval) — SEARCH인 경우만
  - 단일 쿼리: 기존 검색 파이프라인 1회
  - 멀티 쿼리: 최대 3개 서브쿼리로 제한, 병렬 검색

Stage 3: 생성 (Generation)
  - 검색 결과 기반 응답 생성
  - 멀티 쿼리 결과는 하나로 합쳐서 한 번에 생성
```

### 2-2. 최적화 포인트
- Stage 1에서 compress + decide를 단일 프롬프트로 합침 → LLM 호출 1회 절약
- 멀티스텝 분해 시 서브쿼리 최대 3개 제한
- 서브쿼리 병렬 검색 (CompletableFuture 또는 Flux.merge)
- 에이전트 스텝 이벤트 간소화 (3단계에 맞춰)

## 3. 설정
- `app.rag.max-sub-queries: 3` 설정 추가
- `app.rag.merge-analysis: true` — 분석 단계 통합 여부
