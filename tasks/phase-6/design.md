# Phase 6 — UI/UX 개선: 상세 설계

## 기술 스택 변경

- **Tailwind CSS v4** — CSS-first configuration, 유틸리티 클래스
- **shadcn/ui** — Radix UI 기반 고품질 컴포넌트 (소스 복사 방식, 커스텀 자유)
- 기존 `App.css` → Tailwind 유틸리티 + shadcn 컴포넌트로 전면 교체

---

## 1. 컬러 시스템

### HEX → OKLCH 변환

shadcn/ui (Tailwind v4)는 OKLCH 색상 형식을 사용한다. 브랜드 색상 변환:

| 용도 | HEX | OKLCH (근사값) |
|------|------|----------------|
| Background | `#FFEEFF` | `oklch(0.96 0.03 330)` |
| Primary | `#0C0A3E` | `oklch(0.15 0.08 280)` |
| Secondary | `#7B1E7A` | `oklch(0.42 0.18 320)` |
| Minor/Accent | `#B33F62` | `oklch(0.52 0.16 10)` |

### CSS 변수 매핑 (`globals.css`)

```css
:root {
  --background: oklch(0.96 0.03 330);
  --foreground: oklch(0.15 0.08 280);      /* primary = 텍스트 기본색 */
  --primary: oklch(0.15 0.08 280);
  --primary-foreground: oklch(0.98 0 0);
  --secondary: oklch(0.42 0.18 320);
  --secondary-foreground: oklch(0.98 0 0);
  --accent: oklch(0.52 0.16 10);
  --accent-foreground: oklch(0.98 0 0);
  --card: oklch(1 0 0);                    /* 흰색 카드 배경 */
  --card-foreground: oklch(0.15 0.08 280);
  --border: oklch(0.88 0.03 330);          /* 라벤더 톤 보더 */
  --muted: oklch(0.92 0.02 330);
  --muted-foreground: oklch(0.55 0 0);
}
```

---

## 2. 셋업 절차

```bash
cd frontend
pnpm dlx shadcn@latest init -t vite
pnpm dlx shadcn@latest add button select input textarea card badge dialog tabs tooltip scroll-area separator sonner checkbox
```

### 설정 파일 변경

**tsconfig.app.json** — `@/` path alias 추가:
```json
{
  "compilerOptions": {
    "baseUrl": ".",
    "paths": { "@/*": ["./src/*"] }
  }
}
```

**vite.config.ts** — resolve alias 추가:
```ts
resolve: {
  alias: { "@": path.resolve(__dirname, "./src") }
}
```

---

## 3. 컴포넌트별 적용 계획

### 3-1. 사이드바

| 현재 | 변경 |
|------|------|
| `<button className="sidebar-tab">` | `<Tabs>` 컴포넌트 |
| `<div className="document-upload">` | `<Card>` + 드래그앤드롭 영역 |
| `<ul className="document-list">` | `<ScrollArea>` + 커스텀 리스트 |
| `<div className="model-item">` | `<Card>` 변형 |
| purpose 단일 select | `<Checkbox>` 그룹 (다중 선택) |
| `<button>테스트</button>` | `<Button variant="outline" size="sm">` |

### 3-2. 채팅

| 현재 | 변경 |
|------|------|
| `<select className="model-selector">` | `<Select>` (shadcn) + provider 그룹 |
| `<input type="text">` | `<Textarea>` (자동 높이 확장) |
| `<button>전송</button>` | `<Button>` + 화살표 아이콘 |
| `<div className="message">` | `<Card>` 변형, 둥근 버블 |
| `<div className="agent-steps">` | `<Card>` + 이모지 아이콘 + 스피너 |
| `<div className="sources">` | 접힘/펼침 `<details>` 또는 커스텀 아코디언 |
| 없음 | `<Button variant="ghost">` 👍👎 피드백 |

### 3-3. 공통

| 현재 | 변경 |
|------|------|
| `alert()` / `confirm()` | `<Dialog>` 컴포넌트 |
| 없음 | `<Sonner>` 토스트 알림 |
| 없음 | `<Tooltip>` 버튼 힌트 |
| `<Badge>` (CSS) | `<Badge>` (shadcn) |

---

## 4. 모델 등록 Purpose 다중 선택

### 프론트엔드 변경

현재 `<select>` → `<Checkbox>` 그룹:

```tsx
{PURPOSES.map(p => (
  <div key={p} className="flex items-center gap-2">
    <Checkbox
      checked={selectedPurposes.includes(p)}
      onCheckedChange={(checked) => toggle(p, checked)}
    />
    <label>{p}</label>
  </div>
))}
```

### 백엔드 변경

배치 등록 API 추가:

```
POST /api/models/batch
Body: { ...모델정보, purposes: ["CHAT", "QUERY", "RERANK"] }
```

서버에서 각 purpose별로 개별 `llm_model` 레코드 생성.

---

## 5. Thumbs Up/Down 피드백

### 컴포넌트

각 AI 메시지 하단:

```tsx
<div className="flex gap-1">
  <Button variant="ghost" size="sm" onClick={() => submitFeedback('up')}>
    👍
  </Button>
  <Button variant="ghost" size="sm" onClick={() => submitFeedback('down')}>
    👎
  </Button>
</div>
```

### API 연동

기존 `POST /api/chat/feedback` 활용. 선택 상태를 메시지에 저장.

---

## 6. Agent Steps 아이콘 매핑

| step | 현재 | 변경 |
|------|------|------|
| compress | [~] | 🔍 |
| decide | [?] | 🤔 |
| decompose | [/] | 📋 |
| search | [*] | 📄 |
| sub_search | [*] | 📄 |
| sub_answer | [-] | ✏️ |
| sub_done | [-] | ✅ |
| synthesize | [+] | 🔗 |
| generate | [>] | 💬 |
| direct | [>] | 💬 |
| clarify | [?!] | ❓ |

마지막 step(진행 중)에는 로딩 스피너 애니메이션 추가.

---

## 7. 입력창 개선

- `<input>` → `<Textarea>` (shadcn)
- 자동 높이 확장 (1줄 → 최대 5줄)
- Enter: 전송, Shift+Enter: 줄바꿈
- 전송 버튼: 아이콘 (↑ 화살표)

---

## 8. 구현 순서

### Step 1: shadcn/ui 셋업 + 컬러 시스템

1. Tailwind v4 + shadcn/ui 설치 (`init -t vite`)
2. 컬러 변수 설정 (globals.css)
3. 기존 App.css/index.css 제거, Tailwind 기반으로 전환
4. **검증**: 기존 화면이 Tailwind 스타일로 렌더링되는지 확인

### Step 2: 사이드바 리디자인

1. Tabs → shadcn Tabs
2. 문서 업로드 + 목록 → Card + ScrollArea
3. 모델 관리 → Card + Badge + Button
4. 모델 등록 → Checkbox 다중 선택 + 배치 API
5. **검증**: 사이드바 전체가 새 디자인으로 동작

### Step 3: 채팅 영역 리디자인

1. 모델 선택 → shadcn Select
2. 메시지 버블 → Card 변형
3. Agent steps → 이모지 + 스피너
4. 입력창 → Textarea + Enter/Shift+Enter
5. 마크다운 렌더링 스타일 Tailwind로 전환
6. **검증**: 채팅 전체 흐름 동작

### Step 4: 피드백 + 출처 + 토스트

1. 👍👎 피드백 버튼 + API 연동
2. 출처 접힘/펼침
3. Sonner 토스트 (모델 테스트, 문서 업로드 등)
4. **검증**: 피드백 저장, 토스트 표시

### Step 5: 반응형 + 마무리

1. 모바일 사이드바 접힘
2. 키보드 접근성
3. 빈 상태 가이드 메시지
4. **검증**: 모바일/태블릿 대응
