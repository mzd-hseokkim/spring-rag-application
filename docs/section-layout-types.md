# 섹션 레이아웃 유형 정의

## 분류 체계

### A. 텍스트 중심 (Text-focused)

| ID | 유형명 | 설명 | 슬롯 |
|----|--------|------|------|
| `TEXT_FULL` | 전문 텍스트 | 1단 전체 너비 본문. 개요, 서론, 방침 설명 등 | title, body |
| `TEXT_2COL` | 2단 텍스트 | 좌/우 텍스트 영역. 병렬 설명, 현황/개선 등 | title, left, right |
| `TEXT_3COL` | 3단 텍스트 | 3개 컬럼. 특징 나열, 원칙 3가지 등 | title, col1, col2, col3 |
| `TEXT_CALLOUT` | 강조 블록 | 본문 + 핵심 메시지 강조 박스 (인용, 핵심 포인트) | title, body, callout |

### B. 데이터/비교 중심 (Data-focused)

| ID | 유형명 | 설명 | 슬롯 |
|----|--------|------|------|
| `TABLE_FULL` | 전체 표 | 상단 설명 + 전체 너비 표. 일정, 인력, 스펙 등 | title, description, table |
| `COMPARE_2COL` | 2단 비교 | AS-IS / TO-BE, 현행/제안, 장점/단점 비교 | title, leftLabel, leftBody, rightLabel, rightBody |
| `COMPARE_TABLE` | 비교표 | 여러 항목을 표로 비교 (기술 비교, 벤더 비교) | title, description, table |
| `KPI_CARDS` | 수치 카드 | 핵심 수치 3~4개를 카드로 강조. 기대효과, 성과지표 | title, cards[{value, label, description}] |
| `GRID_2X2` | 2×2 그리드 | 4개 영역 균등 배치. SWOT, 4가지 전략 등 | title, cells[4]{title, body} |

### C. 프로세스/흐름 중심 (Flow-focused)

| ID | 유형명 | 설명 | 슬롯 |
|----|--------|------|------|
| `PROCESS_HORIZONTAL` | 수평 프로세스 | 좌→우 단계별 흐름. 방법론, 개발 절차 | title, steps[{label, description}] |
| `PROCESS_VERTICAL` | 수직 타임라인 | 위→아래 시간순 흐름. 일정, 마일스톤 | title, steps[{date, label, description}] |
| `FUNNEL` | 퍼널 | 위→아래 점차 좁아지는 흐름. 단계별 필터링 | title, stages[{label, description}] |
| `CYCLE` | 순환 다이어그램 | 순환 흐름. 유지보수 주기, 반복 프로세스 | title, steps[{label, description}] |
| `STAIRCASE` | 계단식 상승 | 단계별 성숙도, 점진적 발전 | title, steps[{label, description}] |

### D. 구조/관계 중심 (Structure-focused)

| ID | 유형명 | 설명 | 슬롯 |
|----|--------|------|------|
| `HIERARCHY` | 계층 구조 | 트리 형태. 조직도, 시스템 계층 | title, root{label, children[]} |
| `HUB_SPOKE` | 허브-스포크 | 중심 + 주변 요소. 핵심 시스템 + 연계 시스템 | title, center, spokes[{label, description}] |
| `LAYERS` | 레이어 스택 | 수직 적층. 아키텍처 레이어, 기술 스택 | title, layers[{label, description}] |
| `MATRIX` | 매트릭스 | 2축 기준 4분면. 우선순위, 리스크 매트릭스 | title, xAxis, yAxis, quadrants[4]{label, items[]} |

### E. 혼합 (Mixed)

| ID | 유형명 | 설명 | 슬롯 |
|----|--------|------|------|
| `TEXT_IMAGE` | 텍스트+이미지 | 좌(또는 우) 텍스트 + 이미지/다이어그램 영역 | title, body, imagePlaceholder, imageCaption |
| `IMAGE_FULL` | 전체 이미지 | 전체 너비 다이어그램/이미지 + 하단 캡션 및 설명 | title, imagePlaceholder, imageCaption, description |
| `TEXT_TABLE` | 텍스트+표 | 상단 설명 + 하단 표. 가장 범용적 | title, body, table |
| `HEADER_CARDS` | 헤더+카드 | 상단 요약 + 하단 카드 그리드. 기능 목록, 서비스 소개 | title, summary, cards[{icon, title, description}] |
| `SPLIT_DETAIL` | 요약+상세 | 좌측 요약/목록 + 우측 상세 설명 | title, summaryList[], detailBody |

## 실제 제안서 섹션별 권장 레이아웃

| 제안서 섹션 | 추천 레이아웃 | 이유 |
|-------------|-------------|------|
| 사업 개요/배경 | `TEXT_FULL`, `TEXT_CALLOUT` | 서술형, 핵심 목적 강조 |
| 현황 분석 | `COMPARE_2COL`, `TEXT_TABLE` | AS-IS/TO-BE 비교 |
| 시스템 아키텍처 | `LAYERS`, `TEXT_IMAGE` | 계층 구조 시각화 |
| 기능 목록 | `HEADER_CARDS`, `TABLE_FULL` | 기능 나열 + 설명 |
| 개발 방법론 | `PROCESS_HORIZONTAL`, `CYCLE` | 단계별 흐름 |
| 수행 일정 | `PROCESS_VERTICAL`, `TABLE_FULL` | 타임라인 |
| 기대효과 | `KPI_CARDS`, `STAIRCASE` | 수치 강조, 성장 시각화 |
| 투입인력 | `TABLE_FULL`, `HIERARCHY` | 인력표, 조직도 |
| 위험관리 | `MATRIX`, `TABLE_FULL` | 리스크 매트릭스 |
| 유사실적 | `GRID_2X2`, `TEXT_TABLE` | 프로젝트 카드 |
| 유지보수 | `CYCLE`, `PROCESS_HORIZONTAL` | 운영 주기 |

## 구현 우선순위

### 1차 (핵심 — 이것만으로도 80% 커버)
- `TEXT_FULL` — 기본 텍스트
- `TEXT_TABLE` — 텍스트 + 표 (가장 범용)
- `COMPARE_2COL` — AS-IS/TO-BE 비교
- `PROCESS_HORIZONTAL` — 단계별 프로세스
- `KPI_CARDS` — 수치 강조
- `TABLE_FULL` — 전체 표
- `TEXT_IMAGE` — 텍스트 + 다이어그램/이미지 (아키텍처 설명, 시스템 구성도)
- `IMAGE_FULL` — 전체 너비 다이어그램 + 캡션/설명 (전체 구성도, 네트워크 토폴로지)

### 2차 (확장)
- `TEXT_2COL`, `TEXT_3COL` — 다단 텍스트
- `GRID_2X2` — 4분면
- `LAYERS` — 아키텍처 스택
- `HEADER_CARDS` — 기능/서비스 카드
- `PROCESS_VERTICAL` — 타임라인

### 3차 (고급)
- `HIERARCHY`, `HUB_SPOKE` — 구조도
- `CYCLE`, `FUNNEL`, `STAIRCASE` — 특수 시각화
- `MATRIX` — 매트릭스
- `TEXT_IMAGE` — 이미지 포함
- `SPLIT_DETAIL`, `TEXT_CALLOUT` — 특수 혼합
