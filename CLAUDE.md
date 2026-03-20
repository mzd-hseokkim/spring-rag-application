# CLAUDE.md

## Coding Principles (Karpathy Guidelines)

### 1. Think Before Coding

- 구현 전에 가정(assumptions)을 명시적으로 밝힌다. 불확실하면 먼저 질문한다.
- 해석이 여러 가지 가능하면, 조용히 하나를 고르지 말고 선택지를 제시한다.
- 더 단순한 접근법이 있으면 제안하고, 필요하면 반대 의견을 낸다.
- 혼란스러운 부분이 있으면 멈추고, 무엇이 불분명한지 명확히 말한다.

### 2. Simplicity First

- 요청받은 것만 구현한다. 추측성 기능을 추가하지 않는다.
- 한 번만 쓰이는 코드에 추상화를 만들지 않는다.
- 요청되지 않은 "유연성"이나 "설정 가능성"을 넣지 않는다.
- 발생 불가능한 시나리오에 대한 에러 핸들링을 넣지 않는다.
- 200줄로 쓴 것이 50줄로 가능하면, 다시 쓴다.

### 3. Surgical Changes

- 인접 코드, 주석, 포매팅을 "개선"하지 않는다.
- 고장나지 않은 것을 리팩터링하지 않는다.
- 기존 코드 스타일에 맞춘다 (내 선호와 다르더라도).
- 내 변경으로 인해 사용되지 않게 된 import/변수/함수만 제거한다.
- 기존에 있던 dead code는 언급만 하고, 요청 없이 삭제하지 않는다.
- 변경된 모든 줄은 사용자의 요청에 직접 연결되어야 한다.

### 4. Goal-Driven Execution

- 작업을 검증 가능한 목표로 변환한다.
  - "유효성 검사 추가" → "잘못된 입력에 대한 테스트를 작성하고, 통과시킨다"
  - "버그 수정" → "재현 테스트를 작성하고, 통과시킨다"
  - "X 리팩터링" → "리팩터링 전후 테스트가 통과하는지 확인한다"
- 다단계 작업은 단계별 계획과 검증 기준을 명시한다.
- 검증될 때까지 반복한다. 성급하게 완료 선언하지 않는다.

---

## Project Overview

Spring AI 기반 RAG(Retrieval-Augmented Generation) 애플리케이션.
백엔드와 프론트엔드를 하나의 저장소에서 관리하는 monorepo 구조.

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Backend | Java 21+ / Spring Boot 3 | 3.4.4 |
| AI Framework | Spring AI | 1.1.3 |
| Frontend | React + Vite + TypeScript | React 19, Vite 8, TS 5.9 |
| Database | PostgreSQL | 16 (pgvector) |
| Build (BE) | Gradle (Kotlin DSL) | 9.4.1 |
| Build (FE) | pnpm | 10.x |

## Project Structure

```
spring-rag-application/
├── backend/                # Spring Boot 애플리케이션
│   ├── src/main/java/
│   ├── src/main/resources/
│   ├── src/test/
│   └── build.gradle.kts
├── frontend/               # React + Vite 애플리케이션
│   ├── src/
│   ├── public/
│   ├── package.json
│   └── vite.config.ts
├── docker-compose.yml      # PostgreSQL + pgvector
└── CLAUDE.md
```

## Database

- PostgreSQL을 RDB와 Vector DB 용도로 함께 사용한다.
- pgvector 확장을 활용하여 임베딩 벡터를 저장/검색한다.
- 스키마 관리는 Flyway를 사용한다.

## Conventions

- 한국어로 소통한다.
- 커밋 메시지는 영어로 작성한다.
- Backend: 패키지 구조는 도메인 기반 (`feature` 단위)으로 구성한다.
- Frontend: 컴포넌트는 함수형으로 작성하고, 상태관리는 최소한으로 유지한다.
- API 통신은 REST를 기본으로 하되, 스트리밍 응답은 SSE를 사용한다.
