# Spring RAG Application

Spring AI 기반 RAG(Retrieval-Augmented Generation) 애플리케이션.
문서를 업로드하고, AI가 문서 내용을 기반으로 질문에 답변하며, 문서 생성 및 Q&A 리포트 작성까지 지원합니다.

## 주요 기능

### RAG 채팅
- 업로드된 문서 기반 AI 질의응답 (SSE 스트리밍)
- 하이브리드 검색 (벡터 + 키워드, RRF 융합)
- 멀티스텝 추론 에이전트 (최대 3단계)
- 쿼리 확장·압축·재순위화
- 대화 히스토리 관리

### 문서 관리
- PDF, DOCX 등 문서 업로드 및 자동 청킹 (시맨틱/고정)
- 태그 및 컬렉션 기반 문서 분류
- 공개/비공개 문서 관리
- 비동기 임베딩 파이프라인

### AI 문서 생성
- 템플릿 기반 문서 생성 (제안서 등)
- AI 아웃라인 생성 → 섹션별 합성
- PDF/PPTX/HTML 출력 지원
- 실시간 진행률 스트리밍

### Q&A 리포트 생성
- 멀티 페르소나 기반 질의응답 생성
- 문서 분석 기반 맞춤 질문 생성
- HTML 리포트 출력 및 다운로드

### 관리자 대시보드
- 채팅/토큰 사용량 메트릭 및 트렌드 차트
- 파이프라인 트레이스 조회
- 사용자·문서·대화 관리
- 감사 로그

### 기타
- JWT 기반 인증 및 역할 기반 접근 제어 (USER, ADMIN)
- LLM 모델 관리 (Anthropic, Ollama)
- 자동 RAG 품질 평가 (충실도·관련성)
- 속도 제한 (사용자 10req/min, 관리자 30req/min)

## 기술 스택

| 레이어 | 기술 | 버전 |
|--------|------|------|
| Backend | Java 21+ / Spring Boot | 3.4.4 |
| AI Framework | Spring AI (Anthropic, Ollama) | 1.1.3 |
| Frontend | React + Vite + TypeScript | React 19, Vite 8 |
| UI | Tailwind CSS + shadcn/ui | Tailwind 4 |
| Database | PostgreSQL (pgvector) | 16 |
| Cache | Redis | 7 |
| Build (BE) | Gradle (Kotlin DSL) | 9.4.1 |
| Build (FE) | pnpm | 10.x |
| Migration | Flyway | - |

## 프로젝트 구조

```
spring-rag-application/
├── backend/
│   ├── src/main/java/com/example/rag/
│   │   ├── admin/          # 관리자 유틸리티
│   │   ├── agent/           # 멀티스텝 추론 에이전트
│   │   ├── auth/            # JWT 인증 & 보안
│   │   ├── chat/            # RAG 채팅 (SSE 스트리밍)
│   │   ├── common/          # 공통 유틸리티
│   │   ├── config/          # Spring 설정
│   │   ├── conversation/    # 대화 히스토리 관리
│   │   ├── dashboard/       # 대시보드 메트릭
│   │   ├── document/        # 문서 업로드·청킹·파이프라인
│   │   ├── evaluation/      # RAG 품질 평가
│   │   ├── generation/      # AI 문서 생성
│   │   ├── model/           # LLM 모델 관리
│   │   ├── observability/   # 파이프라인 트레이싱
│   │   ├── questionnaire/   # Q&A 리포트 생성
│   │   ├── search/          # 하이브리드 검색·재순위화
│   │   └── settings/        # 시스템 설정
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── db/migration/    # Flyway 마이그레이션 (V1~V25)
│   │   ├── prompts/         # AI 프롬프트 파일 (23개)
│   │   └── templates/       # Thymeleaf 템플릿
│   └── build.gradle.kts
├── frontend/
│   ├── src/
│   │   ├── api/             # REST API 클라이언트
│   │   ├── components/      # UI 컴포넌트
│   │   ├── hooks/           # 커스텀 훅
│   │   ├── pages/           # 라우트 페이지
│   │   └── types/           # TypeScript 타입
│   ├── package.json
│   └── vite.config.ts
├── docker-compose.yml       # PostgreSQL + Redis
└── CLAUDE.md
```

## 시작하기

### 사전 요구사항

- Java 21+
- Node.js 20+ & pnpm 10+
- Docker & Docker Compose

### 1. 인프라 실행

```bash
docker compose up -d
```

PostgreSQL(pgvector)은 `localhost:5434`, Redis는 `localhost:6381`에서 실행됩니다.

### 2. 환경 변수 설정

```bash
# Anthropic API 키 (필수 - Anthropic 모델 사용 시)
export ANTHROPIC_API_KEY=your-api-key

# Tavily API 키 (선택 - 웹 검색 기능 사용 시)
export TAVILY_API_KEY=your-api-key
```

Ollama를 사용하는 경우 별도의 Ollama 서버가 실행 중이어야 합니다.

### 3. 백엔드 실행

```bash
cd backend
./gradlew bootRun
```

백엔드는 `http://localhost:8080`에서 실행됩니다.

### 4. 프론트엔드 실행

```bash
cd frontend
pnpm install
pnpm dev
```

프론트엔드는 `http://localhost:5173`에서 실행됩니다.

## 개발 히스토리

| Phase | 내용 |
|-------|------|
| 1 | Core RAG Pipeline (MVP) |
| 2 | Retrieval Enhancement + Conversation |
| 3 | LLMOps + Quality Assurance |
| 4 | Advanced Enhancement |
| 5 | LLM Model Management (Multi-Model Registry) |
| 6 | UI/UX Improvement (shadcn/ui) |
| 7-8 | Conversation History, Auth, Public Documents |
| 9 | Admin Management, Tags & Collections |
| 10 | WebSocket Streaming + Stop Generation |
| 11 | Admin Dashboard with Metrics & Charts |
| 12 | Automated RAG Evaluation |
| 13 | Batch Embedding, Chunk Strategy Tuning |
| 14 | UX Improvements & Design Renewal |
| 15 | Streamlined Multi-turn RAG (Max 3 Stages) |
| 16 | Table Support, Tag/Collection Sync |
| 17 | Audit Logging & Rate Limiting |
| 18 | AI Document Generation |
| WIP | Questionnaire (Q&A Report Generation) |
