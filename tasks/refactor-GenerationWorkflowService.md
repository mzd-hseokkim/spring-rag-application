# GenerationWorkflowService 리팩토링 계획

## 현황

`GenerationWorkflowService.java` — 995줄, 단일 클래스가 너무 많은 책임을 가짐.

### 책임 분석

| 책임 | 메서드 | 줄 수 |
|---|---|---|
| 레거시 심플 워크플로 | `execute()` | ~70줄 |
| 위자드 Step 2 (목차 추출) | `extractOutline()` | ~80줄 |
| 위자드 Step 3 (요구사항 매핑) | `mapRequirements()` | ~70줄 |
| 위자드 Step 4 (섹션 생성) | `generateWizardSections()` x2, `regenerateSection()` | ~200줄 |
| 위자드 Step 5 (렌더링) | `renderWizardDocument()` | ~50줄 |
| 미배치 요구사항 처리 | `generateSectionsForUnmapped()` | ~50줄 |
| SSE 이벤트 발행 | `emitEvent()`, `emitRequirements()`, `updateStatus()` | ~50줄 |
| JSON 파싱/직렬화 | `parseRequirements...`, `parseMappingFrom...`, `parseSections()`, `toJson()` | ~70줄 |
| 목차 유틸리티 | `collectLeafSections()`, `flattenOutlineNodes()`, `compareKeys()` 등 | ~60줄 |
| 검색/생성 헬퍼 | `generateLeafSection()`, `generateSearchQueries()`, `generateSectionWithRetry()` 등 | ~100줄 |

---

## 리팩토링 원칙

- 각 Step은 **독립적으로 컴파일·테스트 가능**해야 한다.
- Step이 끝날 때마다 기능이 정상 동작하는 상태를 유지한다.
- 하나의 Step이 완전히 끝난 후 다음 Step으로 넘어간다.

---

## Step A — LeafSection + 목차 유틸리티 분리

**위험도: 최저**

이동 대상: 순수 함수만 포함 (외부 의존성 없음)

- `LeafSection` (inner record → 독립 파일)
- `compareKeys()` (static)
- `parseSegment()` (static)
- `collectLeafSections()` (static)
- `flattenOutlineNodes()` (static)
- `buildSectionPlans()` (static)

결과물:
- `LeafSection.java` — record 타입
- `OutlineUtils.java` — static 유틸 클래스

완료 기준:
- [x] 컴파일 성공
- [x] `GenerationWorkflowService`에서 새 클래스를 참조하여 기존과 동일하게 동작

---

## Step B — JSON 파싱 헬퍼 분리

**위험도: 낮음**

이동 대상: `ObjectMapper`만 의존하는 파싱 메서드

- `parseRequirementsFromMapping()`
- `parseMappingFromJson()`
- `parseSections()`
- `toJson()`

결과물:
- `GenerationDataParser.java` — `@Component`

완료 기준:
- [ ] 컴파일 성공
- [ ] `GenerationWorkflowService`가 `GenerationDataParser`를 주입받아 기존과 동일하게 동작

---

## Step C — SSE 이벤트 발행 분리

**위험도: 낮음**

이동 대상: SSE/상태 업데이트 관련 메서드

- `emitEvent()`
- `emitRequirements()`
- `updateStatus()`

결과물:
- `WorkflowEventEmitter.java` — `@Component`, `GenerationEmitterManager` + `GenerationJobRepository` 의존

완료 기준:
- [x] 컴파일 성공
- [ ] 프론트엔드 SSE 수신 정상 동작 확인

---

## Step D — 위자드 Step 4 (섹션 생성) 분리

**위험도: 중간** — Step A, B, C 완료 후 진행

이동 대상:

- `generateWizardSections()` (오버로드 2개)
- `regenerateSection()`
- `generateLeafSection()`
- `generateSearchQueries()`
- `generateSectionWithRetry()`
- `buildRequirementTextForSection()`
- `formatWebSource()`

결과물:
- `WizardSectionService.java` — `@Service`

완료 기준:
- [x] 컴파일 성공
- [ ] 섹션 생성 / 단일 섹션 재생성 정상 동작
- [x] `GenerationWorkflowService`는 `WizardSectionService`에 위임만 함

---

## Step E — 위자드 Step 2·3 + 미배치 요구사항 처리 분리

**위험도: 중간** — Step D 완료 후 진행

이동 대상:

- `extractOutline()`
- `mapRequirements()`
- `generateSectionsForUnmapped()`
- `loadCustomerChunks()`

결과물:
- `WizardAnalysisService.java` — `@Service`

완료 기준:
- [x] 컴파일 성공
- [ ] 요구사항 추출·매핑·목차 구성 정상 동작

---

## Step F — 최종 정리 (선택)

**위험도: 낮음** — Step E 완료 후 진행

- `GenerationWorkflowService`를 각 Step 서비스에 위임하는 퍼사드로 슬림화
- 레거시 `execute()` 를 `SimpleGenerationService.java`로 분리 (선택)
- `renderWizardDocument()`를 `WizardRenderService.java`로 분리 (선택)

완료 기준:
- [x] `GenerationWorkflowService` 200줄 이하 (75줄)
- [ ] 전체 워크플로 E2E 정상 동작

---

## 진행 상황

- [x] Step A
- [x] Step B
- [x] Step C
- [x] Step D
- [x] Step E
- [x] Step F
