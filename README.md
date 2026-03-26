# Spring RAG Application

Spring AI 기반 RAG(Retrieval-Augmented Generation) 애플리케이션.
문서를 업로드하고, AI가 문서 내용을 기반으로 질문에 답변하며, 문서 생성 및 예상 질의서 작성까지 지원합니다.

## 주요 기능

### RAG 채팅
- 업로드된 문서 기반 AI 질의응답 (SSE 스트리밍)
- 하이브리드 검색 (벡터 + 키워드, RRF 융합)
- 멀티스텝 추론 에이전트 (최대 3단계, SEARCH / DIRECT_ANSWER / CLARIFY / WEB_SEARCH)
- 쿼리 확장·압축·라우팅·재순위화
- 대화 히스토리 관리
- Agentic Tools 드롭다운 (웹 검색 토글)
- Tavily 웹 검색 통합 (문서 검색 부재 시 자동 폴백)

### 문서 관리
- PDF, DOCX, Markdown, TXT 문서 업로드
- 자동 청킹 (시맨틱 / 고정 크기, 표 인식)
- 태그 및 컬렉션 기반 문서 분류
- 공개/비공개 문서 관리
- 비동기 배치 임베딩 파이프라인
- 문서 재인덱싱

### AI 문서 생성 (위자드)
5단계 위자드 기반 문서 생성:
1. **설정** — 템플릿 선택, 고객/참조 문서 지정, 웹 검색 옵션
2. **목차 구성** — 고객 문서에서 AI 목차 추출, 편집 가능 (추가/삭제/순서변경)
3. **요구사항 배치** — 요구사항 자동 추출 → 목차에 매핑 (드래그&드롭), 원본 요구사항 번호 보존
4. **내용 생성** — 섹션별 AI 생성 (8가지 레이아웃 템플릿), 개별 재생성/편집, 참조문서 RAG + 웹 검색
5. **렌더링** — HTML 문서 출력, 미리보기, 다운로드
- 목차 및 요구사항 배치 마크다운 다운로드
- 요구사항 추출 결과 캐시 공유 (문서 생성 ↔ 예상 질의서 간)

### 예상 질의서 생성
- 멀티 페르소나 기반 예상 질문/답변 생성
- 고객 문서 → 요구사항 추출 → 제안서 갭 분석 → 질문 생성 파이프라인
- RAG 모드 / LLM 모드 선택 가능 (제안서 대응 분석)
- 참조 문서 RAG 검색 + Tavily 웹 검색 통합
- 요구사항 추출 결과 2중 캐시 (Redis L1 + PostgreSQL L2)
- HTML 리포트 출력 및 다운로드

### 관리자 대시보드
- 채팅/토큰 사용량 메트릭 및 트렌드 차트
- 파이프라인 트레이스 조회
- 사용자·문서·대화 관리
- LLM 모델 관리
- 감사 로그
- 요구사항 캐시 관리 (전체 초기화)

### 기타
- JWT 기반 인증 및 역할 기반 접근 제어 (USER, ADMIN)
- LLM 모델 관리 (Anthropic, Ollama, Azure OpenAI 임베딩)
- 자동 RAG 품질 평가 (충실도·관련성)
- 속도 제한 (사용자 10req/min, 관리자 30req/min)
- 아바타 이미지 업로드
- 목록 항목 인라인 제목 편집

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
│   │   ├── agent/          # 멀티스텝 추론 에이전트
│   │   ├── audit/          # 감사 로그
│   │   ├── auth/           # JWT 인증 & 보안
│   │   ├── chat/           # RAG 채팅 (SSE 스트리밍)
│   │   ├── common/         # 공통 유틸리티
│   │   ├── config/         # Spring 설정
│   │   ├── conversation/   # 대화 히스토리 관리
│   │   ├── dashboard/      # 대시보드 메트릭
│   │   ├── document/       # 문서 업로드·청킹·파이프라인
│   │   ├── evaluation/     # RAG 품질 평가
│   │   ├── generation/     # AI 문서 생성 (위자드)
│   │   ├── model/          # LLM 모델 관리
│   │   ├── observability/  # 파이프라인 트레이싱
│   │   ├── questionnaire/  # 예상 질의서 생성
│   │   ├── search/         # 하이브리드 검색·재순위화
│   │   └── settings/       # 시스템 설정
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── db/migration/   # Flyway 마이그레이션 (V1~V31)
│   │   ├── prompts/        # AI 프롬프트 파일 (31개)
│   │   └── templates/      # Thymeleaf 템플릿
│   └── build.gradle.kts
├── frontend/
│   ├── src/
│   │   ├── api/            # REST API 클라이언트
│   │   ├── components/     # UI 컴포넌트
│   │   ├── hooks/          # 커스텀 훅
│   │   ├── pages/          # 라우트 페이지
│   │   └── types/          # TypeScript 타입
│   ├── package.json
│   └── vite.config.ts
├── docker-compose.yml      # PostgreSQL + Redis (인프라)
├── docker-compose-dev.yml  # 풀스택 개발 환경 (DB + Redis + Backend + Frontend)
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

### 풀스택 개발 환경 (Docker)

```bash
docker compose -f docker-compose-dev.yml up --build
```

Backend, Frontend, PostgreSQL, Redis가 한번에 실행됩니다. Podman도 지원합니다.

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
| 18 | AI Document Generation (Wizard) |
| 19 | Questionnaire — Persona-based Q&A Report |
| 20 | Requirement-driven Generation Workflow (Phase A~D) |
| 21 | Web Search Integration (Tavily + Agentic Tools) |
| 22 | Requirement Cache Sharing, Markdown Download, Title Editing |
