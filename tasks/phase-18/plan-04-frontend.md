# Plan 04 — 프론트엔드

문서 생성 UI, 진행률 표시, 미리보기/다운로드 기능을 설계한다.

---

## 1. 라우팅 및 페이지 구조

```
기존 라우트:
  / → MainPage (채팅)
  /settings → SettingsPage
  /admin/* → AdminLayout

추가 라우트:
  /generate → GeneratePage (문서 생성 메인)
  /admin/templates → AdminTemplatesPage (템플릿 관리)
```

### 1-1. MainPage에서의 진입점

사이드바에 "문서 생성" 탭 추가 또는 채팅 인풋 영역에 생성 버튼 추가.

```
사이드바 탭: [대화] [문서] [생성]
                              ↑ 새로 추가
```

---

## 2. GeneratePage 설계

### 2-1. 3단계 UI 플로우

```
┌─────────────────────────────────────────────────┐
│  Step 1: 입력                                    │
│  ┌─────────────────────────────────────────────┐ │
│  │ 템플릿 선택 [기술 제안서 ▼]                    │ │
│  │                                             │ │
│  │ 입력 방식:  ○ 텍스트 입력  ○ 파일 업로드       │ │
│  │                                             │ │
│  │ ┌─────────────────────────────────────────┐ │ │
│  │ │ 제안요청서 내용을 입력하세요...              │ │ │
│  │ │                                         │ │ │
│  │ │                                         │ │ │
│  │ └─────────────────────────────────────────┘ │ │
│  │                                             │ │
│  │ [선택] RAG 필터: 태그 [___] 컬렉션 [___]      │ │
│  │ [선택] ☑ AI 리뷰 활성화                       │ │
│  │                                             │ │
│  │              [문서 생성 시작 →]                │ │
│  └─────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘

          ↓ 생성 시작 후

┌─────────────────────────────────────────────────┐
│  Step 2: 생성 중 (진행률)                         │
│  ┌─────────────────────────────────────────────┐ │
│  │ 📄 기술 제안서 생성 중...                      │ │
│  │                                             │ │
│  │ ━━━━━━━━━━━━━━━━░░░░░ 60% (3/5 섹션)         │ │
│  │                                             │ │
│  │ ✅ 요약 — 완료                                │ │
│  │ ✅ 문제 분석 — 완료                            │ │
│  │ ✅ 제안 솔루션 — 완료                          │ │
│  │ ⏳ 추진 일정 — 생성 중...                      │ │
│  │ ○ 기대 효과 — 대기                            │ │
│  │                                             │ │
│  │ [섹션 미리보기]                                │ │
│  │ ┌─────────────────────────────────────────┐ │ │
│  │ │ ## 제안 솔루션                            │ │ │
│  │ │ 본 제안은 다음과 같은 3단계 접근법을...      │ │ │
│  │ │ • 핵심 포인트 1                           │ │ │
│  │ │ • 핵심 포인트 2                           │ │ │
│  │ └─────────────────────────────────────────┘ │ │
│  └─────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘

          ↓ 생성 완료 후

┌─────────────────────────────────────────────────┐
│  Step 3: 결과 (미리보기 + 다운로드)                │
│  ┌─────────────────────────────────────────────┐ │
│  │ ✅ 문서 생성 완료!                             │ │
│  │                                             │ │
│  │ [미리보기]  [PDF 다운로드]  [다시 생성]          │ │
│  │                                             │ │
│  │ ┌─────────────────────────────────────────┐ │ │
│  │ │        ┌───────────────────┐             │ │ │
│  │ │        │   문서 미리보기     │             │ │ │
│  │ │        │   (HTML iframe     │             │ │ │
│  │ │        │    또는 렌더링)     │             │ │ │
│  │ │        │                   │             │ │ │
│  │ │        └───────────────────┘             │ │ │
│  │ └─────────────────────────────────────────┘ │ │
│  └─────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘
```

---

## 3. 컴포넌트 구조

```
frontend/src/
├── pages/
│   └── GeneratePage.tsx              ← 문서 생성 메인 페이지
├── components/
│   └── generation/
│       ├── TemplateSelector.tsx      ← 템플릿 선택 드롭다운
│       ├── GenerationInput.tsx       ← 텍스트 입력 / 파일 업로드
│       ├── GenerationOptions.tsx     ← RAG 필터, 리뷰 옵션
│       ├── GenerationProgress.tsx    ← 진행률 표시 (SSE)
│       ├── SectionPreview.tsx        ← 개별 섹션 미리보기
│       └── GenerationResult.tsx      ← 완료 화면 (미리보기 + 다운로드)
├── hooks/
│   └── useGeneration.ts             ← 생성 API 호출 + SSE 구독
└── api/
    └── generation.ts                ← REST API 클라이언트
```

---

## 4. API 클라이언트

```typescript
// api/generation.ts

export interface GenerationRequest {
  templateId: string;
  userInput: string;
  conversationId?: string;
  options?: {
    includeReview?: boolean;
    tagIds?: string[];
    collectionIds?: string[];
  };
}

export interface GenerationJob {
  id: string;
  status: 'PLANNING' | 'GENERATING' | 'REVIEWING' | 'RENDERING' | 'COMPLETE' | 'FAILED';
  currentSection: number;
  totalSections: number;
  outputFormat: string;
  errorMessage?: string;
  createdAt: string;
}

export interface DocumentTemplate {
  id: string;
  name: string;
  description: string;
  outputFormat: string;
  sectionSchema: object;
}

export const generationApi = {
  // 템플릿
  getTemplates: () => client.get<DocumentTemplate[]>('/api/templates'),
  getTemplate: (id: string) => client.get<DocumentTemplate>(`/api/templates/${id}`),

  // 생성
  startGeneration: (req: GenerationRequest) => client.post<GenerationJob>('/api/generations', req),
  getJob: (id: string) => client.get<GenerationJob>(`/api/generations/${id}`),
  getJobs: () => client.get<GenerationJob[]>('/api/generations'),
  deleteJob: (id: string) => client.delete(`/api/generations/${id}`),

  // 파일
  getDownloadUrl: (id: string) => `/api/generations/${id}/download`,
  getPreviewUrl: (id: string) => `/api/generations/${id}/preview`,
};
```

---

## 5. SSE 진행률 Hook

```typescript
// hooks/useGeneration.ts

interface GenerationState {
  job: GenerationJob | null;
  sections: SectionPreview[];
  isGenerating: boolean;
  error: string | null;
}

interface SectionPreview {
  key: string;
  title: string;
  status: 'pending' | 'generating' | 'complete';
  preview?: string;
}

export function useGeneration() {
  const [state, dispatch] = useReducer(generationReducer, initialState);

  const startGeneration = async (request: GenerationRequest) => {
    // 1) POST로 생성 시작
    const job = await generationApi.startGeneration(request);
    dispatch({ type: 'JOB_STARTED', payload: job });

    // 2) SSE 구독
    const eventSource = new EventSource(
      `/api/generations/${job.id}/stream`,
      { withCredentials: true }
    );

    eventSource.addEventListener('status', (e) => {
      const data = JSON.parse(e.data);
      dispatch({ type: 'STATUS_CHANGED', payload: data });
    });

    eventSource.addEventListener('progress', (e) => {
      const data = JSON.parse(e.data);
      dispatch({ type: 'PROGRESS_UPDATED', payload: data });
    });

    eventSource.addEventListener('section_complete', (e) => {
      const data = JSON.parse(e.data);
      dispatch({ type: 'SECTION_COMPLETED', payload: data });
    });

    eventSource.addEventListener('complete', (e) => {
      const data = JSON.parse(e.data);
      dispatch({ type: 'GENERATION_COMPLETE', payload: data });
      eventSource.close();
    });

    eventSource.addEventListener('error', (e) => {
      dispatch({ type: 'GENERATION_ERROR', payload: e });
      eventSource.close();
    });
  };

  return { ...state, startGeneration };
}
```

---

## 6. 미리보기 및 다운로드

### 6-1. HTML 미리보기

```typescript
// GenerationResult.tsx
function GenerationResult({ jobId }: { jobId: string }) {
  const previewUrl = generationApi.getPreviewUrl(jobId);
  const downloadUrl = generationApi.getDownloadUrl(jobId);

  return (
    <div>
      {/* HTML 미리보기: iframe 또는 sanitized HTML 렌더링 */}
      <iframe
        src={previewUrl}
        className="w-full h-[600px] border rounded"
        sandbox="allow-same-origin"
        title="문서 미리보기"
      />

      <div className="flex gap-2 mt-4">
        <a href={downloadUrl} download>
          <Button>다운로드</Button>
        </a>
        <Button variant="outline" onClick={onRegenerate}>
          다시 생성
        </Button>
      </div>
    </div>
  );
}
```

### 6-2. 다운로드 처리

```typescript
const handleDownload = async (jobId: string) => {
  const response = await fetch(generationApi.getDownloadUrl(jobId), {
    headers: { Authorization: `Bearer ${token}` },
  });
  const blob = await response.blob();
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = response.headers.get('Content-Disposition')
    ?.split('filename=')[1] || 'document';
  a.click();
  window.URL.revokeObjectURL(url);
};
```

---

## 7. 어드민 템플릿 관리 페이지

```
/admin/templates 라우트 추가

AdminTemplatesPage:
├── 템플릿 목록 (DataTable)
│   ├── 이름, 포맷, 공용 여부, 생성일
│   └── 액션: 편집, 삭제, 테스트 생성
├── 템플릿 생성/편집 다이얼로그
│   ├── 기본 정보 (이름, 설명, 포맷)
│   ├── 섹션 스키마 편집 (JSON 에디터)
│   ├── 시스템 프롬프트 편집 (텍스트 에디터)
│   └── PPTX 마스터 파일 업로드 (포맷이 PPTX일 때)
└── 테스트 생성 (샘플 입력으로 빠른 생성 테스트)
```

---

## 8. 생성 이력

사이드바 또는 별도 탭에서 과거 생성 이력을 확인할 수 있다.

```
생성 이력 목록:
┌─────────────────────────────────────────┐
│ 📄 기술 제안서 — 2026-03-23 14:30  ✅    │
│ 📄 사업 보고서 — 2026-03-22 09:15  ✅    │
│ 📄 프레젠테이션 — 2026-03-21 16:00  ❌    │
└─────────────────────────────────────────┘
각 항목 클릭 → 미리보기/다운로드 또는 에러 확인
```
