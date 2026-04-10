# 목차 생성 품질 개선 계획

## 배경

제안서 위자드의 Step 2 — 목차 추출 단계에서 다음 4가지 품질 문제가 동시에 관찰되었다.
이 문서는 4가지 문제를 정의하고, 단계별 해결 계획을 정의한다.

---

## 문제 정의

### 문제 1: 형제(sibling) 섹션 간 중복

같은 부모 아래의 형제 섹션들이 동일한 하위 항목을 반복 생성한다.

**관찰 사례** (II 챕터 = 사업관리 방안):

| 주제 | 분포 | 횟수 |
|---|---|---|
| 추진 일정/로드맵 | II.1.2 + II.2.1 + II.3.2 + II.4.1 | 4번 |
| 개발 방법론 및 품질관리 | II.1.5 + II.2.2 + II.3.3 + II.4.2 | 4번 |
| 조직 구성·역할 분담 | II.2.3 + II.3.1 + II.4.3 | 3번 |
| 위험 관리 및 대응 전략 | II.2.4 + II.3.5 + II.4.4 | 3번 |
| 기술 이전·운영 인수 | II.1.4 + II.2.5 + II.3.4 + II.4.5 | 4번 |

II.1(추진전략) / II.2(추진체계) / II.3(수행방안) / II.4(프로젝트관리) 4개 형제가
모두 사업/관리 관점이라는 공통점을 가져, 각각 같은 명백한 하위 주제를 만들어낸다.

### 문제 2: 챕터 간(cross-chapter) 중복

부모가 다른 챕터들 사이에서도 동일/유사 주제가 반복 등장한다.

**관찰 사례** (III·IV·V 사이):

| 주제 | 분포 | 비고 |
|---|---|---|
| 성능/응답속도 | III.3 + IV.5.3 + V.2.6 | 기술 ↔ 품질 ↔ 운영 |
| 데이터 검증 | III.2.5 + V.2.5 | 기술 ↔ 품질관리 |
| AI 안전성/가드레일 | III.1.3 + IV.1.4 + IV.3.7 + V.2.3 | **4중 중복** (IV 안에서도 자기 중복) |
| 보안/암호화 | III.4.4 + IV.3 전체 | 챕터 경계 모호 |

### 문제 3: RFP 명시 의무 항목 누락

RFP가 "제안서에 반드시 포함하라"고 명시한 작성 항목 중 일부가 생성된 목차에 빠져 있다.

**관찰 사례** (예시):

- "제안기술의 우수성과 비교성을 기술" → 표면적·짧게만 등장
- "타 PPP 사용자 사례" → 누락
- "기획부터 산출물까지의 반영 여부" → 누락
- "RFP B 4쪽 ~ 기술적용 ..." → 누락

### 문제 4: 평가 배점 가중치 무시

RFP가 제시한 평가 배점이 목차 분배에 반영되지 않는다.

**관찰 사례** (예시):

- II.1 사업이해 (5점) → children 6개
- III (4점) → children 6개
- IV.1 (3점) → children 7개
- V.2 (5점) → children 7개

5점 섹션과 3점 섹션의 children 수가 거의 같거나 오히려 역전되어 있다.
산술적·균등 분배가 일어나고 있다는 증거다.

---

## 공통 뿌리(Root Causes)

### 뿌리 1: leaf 확장이 격리된 병렬 호출

`OutlineExtractor.extractWithFixedTopLevel()`(파일 라인 320~339)와
`reduceOutline()`(라인 495~512)는 모든 leaf 노드를 병렬로 LLM에 보내 children을 생성한다.

각 호출은 다음을 알지 못한다:
- 다른 형제 leaf가 무엇을 만들고 있는지/만들었는지
- 다른 챕터에서 이미 어떤 주제를 다루고 있는지

→ 문제 1, 2의 직접 원인.

### 뿌리 2: RFP 정보 모델이 빈약함

`RequirementExtractor`가 추출하는 `Requirement` 레코드 (`Requirement.java:3-9`):
```java
public record Requirement(String id, String category, String item, String description, String importance) {}
```

추출 프롬프트(`questionnaire-extract-requirements.txt:11-15`)는 다음만 추출한다:
- 평가항목 단위 (대분류 > 중분류)
- 중요도 라벨 "상" / "중" / "하"

다음을 추출하지 않는다:
- **제안서 의무 작성 항목** (기능 요구사항이 아닌, 작성 의무가 부여된 항목)
- **정량 배점** (5점, 4점 등 점수 그대로)

게다가 `importance`를 "상/중/하"로 강제 변환하는 과정에서 정량 정보(5점 ↔ 4점)가 손실된다.

→ 문제 3, 4의 직접 원인.

### 뿌리 3: 가중치 인지 없는 균등 확장

`expandSection()`의 프롬프트(`OutlineExtractor.java:566`):
> 하위 항목 2~5개, 필요 시 그 아래 소분류 2~5개

모든 leaf가 동일한 children 예산을 받는다. 가중치/배점에 따른 차등이 전혀 없다.

→ 문제 4의 직접 원인.

---

## 사용자 결정 사항

### 결정 1: 단계별 분리(Step 1 → Step 2)

4개 문제를 한 번에 처리하지 않고 두 단계로 나누어 점진적으로 해결한다.
- **Step 1**: 글로벌 토픽 원장 도입 (문제 1, 2 해결)
- **Step 2**: 의무 항목 추출 + 가중치 인지 (문제 3, 4 해결)

이유: 두 묶음의 검증 방법이 다르고(전자: "중복이 사라졌나", 후자: "의무 항목이 다 들어갔나"),
Step 1이 끝난 상태에서 Step 2를 평가하면 더 정확한 측정이 가능하다.

### 결정 2: 가중치 추출 실패 시 fallback = A + C

배점 정보가 없거나 추출 실패 시:
- **A**: 기존 importance 라벨을 weight로 변환 (상=5, 중=3, 하=1)
- **C**: 사용자에게 "배점 정보 없음, 균등 분배" 경고 표시
→ 둘 다 적용. 부드러운 fallback + 명시적 경고.

---

## Step 1 — 글로벌 토픽 원장 (Sequential Ledger)

**목표**: 문제 1, 2 해결.
**원칙**: leaf 확장은 항상 "이미 다른 leaf가 만들어둔 주제 목록"을 알아야 한다.

### 변경 내용

#### 1.1 `OutlineExtractor.expandSection()` 시그니처 확장
```java
private OutlineNode expandSection(
    ChatClient client,
    OutlineNode topSection,
    String suggestions,
    String keyPrefix,
    String titlePath,
    String topicLedger        // NEW
)
```

`topicLedger` 형식 (예시):
```
[II.1 추진전략]
  - II.1.1 사업 비전 및 핵심 전략
  - II.1.2 단계별 추진 로드맵
[III.1 시스템 아키텍처]
  - III.1.1 전체 구성도
  ...
```

#### 1.2 프롬프트에 원장 주입
`expandSection()`의 프롬프트에 다음 블록 추가:
```
## ⚠️ 이미 다른 섹션에 배치된 주제 (절대 중복 금지)
아래 주제들은 이미 다른 섹션이 다루기로 결정했습니다.
- 같은 주제는 절대 다시 만들지 마세요
- 의미상 동일한 주제(예: "성능 관리" ↔ "응답속도 최적화")도 중복으로 간주
- 이 항목 고유의 관점이 없다면 children을 비워도 됩니다 (빈 배열 반환 가능)
- 이 항목이 다루기에 더 자연스러운 주제만 선택하세요

[원장 내용]
```

#### 1.3 순차 처리로 전환

`extractWithFixedTopLevel()` 변경:
- 기존: `Semaphore`로 leaf 병렬 확장 (라인 320~339)
- 변경: leaf를 key 오름차순으로 정렬 후 **순차** 호출
- 매 호출 결과(생성된 children의 key + title)를 누적 ledger에 추가
- 다음 호출에 누적 ledger 전달

`reduceOutline()` Pass 2도 동일 패턴 적용 (라인 495~512).

#### 1.4 leaf 처리 순서

key 오름차순 (I → II → III → ...) 사용.
이유: 사업 → 기술 → 운영의 자연스러운 순서로 흘러가서 후순위 섹션이 선순위 섹션의 주제를 회피하기 좋다.

#### 1.5 진행률 이벤트 보존

순차 처리로 바뀌면서 leaf별 진행 메시지를 명확하게 발행:
- "권장 목차의 N개 항목 중 X번째를 구성하고 있습니다 (제목)"

### 변경 범위

| 파일 | 변경 종류 |
|---|---|
| `OutlineExtractor.java` | `extractWithFixedTopLevel()`, `reduceOutline()` 순차화 / `expandSection()` 시그니처·프롬프트 수정 / 새 헬퍼 `appendToLedger()` |

신규 파일 없음. 수정 1개 파일.

### 완료 기준

- [ ] 컴파일 성공
- [ ] 기존 outline 추출 케이스가 동일하게 동작 (원장 비어있을 때 == 기존 동작)
- [ ] 4중·3중 형제 중복 케이스가 사라지는지 수동 검증 (이전과 동일 RFP로 재생성)
- [ ] cross-chapter 중복 케이스(AI 안전성, 성능 등)가 사라지는지 수동 검증
- [ ] ledger 누적 로그(`log.info`)로 어떤 주제가 어느 leaf에 할당되었는지 추적 가능

### 알려진 트레이드오프

- **지연시간 증가**: leaf가 N개라면 LLM 호출이 N번 직렬. 기존 병렬(MAX_PARALLEL=3)에 비해 약 3배 증가 예상.
- **순서 의존성**: 먼저 처리되는 leaf가 주제를 "선점"한다. key 오름차순이 일반적으로 자연스러운 순서이지만, 잘못된 RFP에서 이상한 결과가 나올 가능성이 있다.

지연시간이 사용자 체감상 너무 길면 후속 최적화로 "부모 그룹 간 병렬 + 그룹 내 순차" 절충안을 검토.

---

## Step 2 — 의무 항목 추출 + 가중치 인지 확장

**목표**: 문제 3, 4 해결.
**전제**: Step 1이 완료되어 ledger 인프라가 존재한다.

### 2.1 정보 모델 확장

#### `Requirement` 레코드에 weight 필드 추가
```java
public record Requirement(
    String id,
    String category,
    String item,
    String description,
    String importance,
    Integer weight        // NEW: 정량 배점, null이면 미확보
) {}
```

기존 `importance` 필드는 유지 (호환성 + fallback용).

#### 신규 record `MandatoryItem`
```java
public record MandatoryItem(
    String id,             // MAND-01, MAND-02 ...
    String title,          // 예: "제안기술의 우수성과 비교성"
    String description,    // 무엇을 어떻게 다뤄야 하는지
    String sourceHint      // 예: "RFP p.97" — 추적용
) {}
```

#### 신규 record `RfpMandates`
```java
public record RfpMandates(
    List<MandatoryItem> mandatoryItems,
    Map<String, Integer> evaluationWeights,   // 섹션명 → 점수
    Integer totalScore                          // 총점 (예: 100)
) {}
```

### 2.2 신규 추출 단계: `RfpMandateExtractor`

새 서비스 클래스. RFP 청크를 입력받아 `RfpMandates`를 반환.

**프롬프트 핵심 지침**:
- "제안서 작성 시 반드시 포함하라고 명시된 작성 항목"을 추출 (기능 요구사항과 별개)
- "평가항목 + 배점표"가 있으면 정량 점수 그대로 추출 (5점, 4점 등)
- 출력은 JSON: `{"mandatoryItems": [...], "evaluationWeights": {...}, "totalScore": 100}`

새 프롬프트 파일: `prompts/generation-extract-rfp-mandates.txt`

### 2.3 `RequirementExtractor` 프롬프트 수정

`questionnaire-extract-requirements.txt` 변경:
- "배점이 명시되어 있으면 그대로 weight 필드(정수)에 기록" 추가
- 기존 importance 라벨(상/중/하)도 그대로 유지

### 2.4 `WizardAnalysisService.extractOutline()` 변경

```
Phase 1.5: 권장 목차 감지                          (기존)
Phase 1.6: RfpMandateExtractor 호출                (NEW)
Phase 2:   요구사항 추출 (weight 포함)             (수정)
Phase 3:   목차 추출 (의무항목 + 가중치 + 원장)    (수정)
Phase 3.5: 의무 항목 커버리지 검증 + 보충          (NEW)
```

`Phase 1.6` 결과(`RfpMandates`)는 job 엔티티에 저장하여 후속 단계에서 재사용한다.
저장 형식은 기존 `requirementMapping` JSON과 같은 방식으로 추가 필드.

### 2.5 가중치 인지 확장

`expandSection()`에 가중치 정보 전달:
```java
private OutlineNode expandSection(
    ...,
    Integer weight,        // NEW: 이 섹션의 배점
    Integer totalWeight,   // NEW: 총점
    ...
)
```

프롬프트 변경:
```
## 배점 안내
이 섹션의 배점: %d점 / 총 %d점
배점 비율: %.1f%%

## 배점 비례 children 수 가이드
- 5점 이상: children 5~7개, 필요 시 3단계까지 전개
- 3~4점: children 4~5개
- 1~2점: children 2~3개, 2단계로 종료
- 배점 미확보: 균등 2~5개
```

### 2.6 가중치 fallback (결정 2: A + C)

배점 추출 실패 시:
- `Requirement.weight`가 모두 null인 경우
- `RfpMandates.evaluationWeights`가 비어있는 경우

**A. 변환**: `importance` → `weight` 매핑 적용
- "상" → 5
- "중" → 3
- "하" → 1

**C. 사용자 경고**:
- `WorkflowEventEmitter`로 경고 이벤트 발행
- 메시지: "이 RFP에서 정량 배점을 찾지 못했습니다. 중요도(상/중/하)를 기준으로 균등 분배합니다."
- 프론트엔드에서 노란색 배너 등으로 표시

### 2.7 의무 항목 강제 배치

`expandSection()` 프롬프트에 추가 블록:
```
## 이 섹션에 배치할 의무 작성 항목
다음은 RFP가 제안서에 반드시 포함하라고 명시한 항목들 중,
이 섹션에 들어갈 가능성이 있는 것들입니다.
관련된 항목이 있으면 반드시 children 중 하나로 만드세요.

[의무 항목 후보 목록 — 사전 필터링 적용]
```

후보 필터링: 모든 의무 항목을 모든 leaf에 보내면 토큰 낭비.
사전 단계에서 의무 항목을 임베딩 또는 카테고리 매칭으로 후보 leaf에 미리 분배.

### 2.8 의무 항목 커버리지 검증 (Phase 3.5)

outline 생성 후, 모든 `MandatoryItem`이 어떤 leaf에 매핑되었는지 확인:
- LLM 호출: "다음 의무 항목들이 이 outline의 어떤 leaf에서 다뤄지고 있는가?"
- 누락된 항목 → `generateSectionsForUnmapped`와 유사 패턴으로 자동 보충
- 그래도 누락이 있으면 사용자에게 경고

### 변경 범위

| 파일 | 변경 종류 |
|---|---|
| `Requirement.java` | `weight` 필드 추가 |
| `MandatoryItem.java` (NEW) | record 정의 |
| `RfpMandates.java` (NEW) | record 정의 |
| `RfpMandateExtractor.java` (NEW) | 신규 서비스 |
| `prompts/generation-extract-rfp-mandates.txt` (NEW) | 의무 항목 + 배점 추출 프롬프트 |
| `prompts/questionnaire-extract-requirements.txt` | weight 필드 추가 |
| `WizardAnalysisService.java` | Phase 1.6, Phase 3.5 추가 |
| `OutlineExtractor.java` | `expandSection()` 가중치/의무항목 인자 + 프롬프트 |
| `prompts/generation-extract-outline.txt` | 가중치/의무항목 섹션 추가 |
| `WorkflowEventEmitter.java` | 경고 이벤트 메서드 |
| `GenerationDataParser.java` | `RfpMandates` JSON 파싱/직렬화 |

신규 파일 4개, 수정 파일 7~8개.

### 완료 기준

- [ ] 컴파일 성공
- [ ] RFP에 배점이 명시된 케이스에서 가중치가 추출되어 children 수에 반영되는지 검증
- [ ] RFP에 배점이 없는 케이스에서 importance fallback이 동작하고 사용자 경고가 표시되는지 검증
- [ ] 이전에 누락되었던 의무 항목들("제안기술의 우수성과 비교성" 등)이 outline에 포함되는지 검증
- [ ] 가중치 5점 섹션 children 수 ≥ 가중치 3점 섹션 children 수 (역전 없음)
- [ ] Phase 3.5 커버리지 검증 로그로 의무 항목 매핑 추적 가능

### 알려진 트레이드오프

- 추가 LLM 호출 (Phase 1.6 + Phase 3.5) → 비용 + 지연시간 증가
- 의무 항목 사전 필터링 알고리즘이 부정확하면 LLM이 잘못된 후보를 받음
- 정보 모델 확장으로 기존 job 데이터의 마이그레이션 필요 가능 (또는 nullable 필드로 호환)

---

## 진행 순서

1. **Step 1 시작 전 합의**
   - 본 문서 검토 및 승인
   - 검증용 RFP 샘플 결정 (이미지에 등장한 그 RFP를 기준으로)

2. **Step 1 구현**
   - `OutlineExtractor.java` 수정
   - 수동 검증: 이전 RFP로 재생성 → 4중/3중 중복 사라졌는지 확인
   - 커밋

3. **Step 1 검증 후 Step 2 시작**
   - 정보 모델 확장 → 추출기 → 파이프라인 통합 → 검증
   - 각 변경마다 단위 검증 + 마지막에 종합 검증
   - 커밋

4. **회고**
   - 4가지 문제 모두 해결되었는지 최종 확인
   - 새로 발견된 부수 문제가 있다면 별도 후속 task로 분리

---

## Step 3 회고 및 RFP-agnostic 재정렬 (2026-04-10)

### Iteration history (v3 → v6)

Step 1, 2 완료 후 Step 3를 4번 iteration 했다. 매 iteration마다 한 가지 개선과 한 가지 회귀가 동시에 발생하는 **hill-climbing 패턴**이 관찰되었다.

### 근본 진단 — 잘못된 접근 방식

iteration의 패턴은 다음과 같았다:
1. 특정 RFP의 출력에서 구체적 문제 식별
2. 그 구체적 문제를 해결하는 **specific 예시**를 프롬프트에 추가
3. → LLM의 attention budget을 잠식하여 기존 룰이 약화됨
4. → 새 회귀 발생

**근본 문제는 hill-climbing이 아니라 "RFP-specific 예시를 프롬프트에 박아넣는" 접근 방식 자체였다.**

이 방식의 결과:
- 시스템이 점점 한 RFP에만 fit되어 감
- 다른 RFP로 가면 동일한 품질 보장 못함
- iteration할수록 다음 RFP에 대한 일반화 능력이 오히려 떨어짐
- 한 RFP의 결과를 evaluation 기준으로 삼는 것 자체가 over-fitting을 유도

### 핵심 원칙 (다음 iteration의 기반)

목차 생성 시스템은 **임의의 RFP에 대해 일관되게 작동**해야 한다. 이를 위한 원칙:

1. **프롬프트에 RFP-specific 표현 금지**
   - 특정 도메인 용어 (특정 산업·제품·기술명) 금지
   - 특정 chapter/section 명칭 예시 금지
   - 특정 카테고리·prefix 가정 금지
   - 예시는 반드시 추상 placeholder (`<섹션 A>`, `<요구사항 ID>`) 또는 명백히 다양한 도메인 사용

2. **구조적 룰만 프롬프트에**
   - 패턴 식별 룰 ("두 sibling이 의미상 겹치면 perspective split")
   - 일반 매트릭스 (WHAT/HOW-tech/WHY/HOW-method/OPS/MGMT/CTRL-tech/CTRL-mgmt)
   - 정량 규칙 (배점 비례, REQ-ID uniqueness)

3. **코드 차원 enforcement 우선**
   - 코드로 검증 가능한 것(REQ-ID dedup, weight 비례, coverage)은 코드에서 강제
   - 프롬프트는 코드가 못 하는 의미적 판단만 담당

4. **검증은 다중 도메인에서**
   - 한 RFP의 출력 품질 ≠ 시스템 품질
   - 시스템 평가는 최소 2~3개 다른 도메인 RFP에서 일관된 결과로 검증
   - 단일 RFP fitting은 개발 도구가 아닌 anti-pattern

### Cleanup 작업 (2026-04-10 적용)

위 원칙에 따라 다음을 수행했다:

| 파일 | 변경 |
|---|---|
| `prompts/generation-plan-expansion.txt` | v2 → v3. 모든 RFP-specific 예시 제거. 추상 관점 매트릭스, abstract placeholder 예시(`Section.A`, `Section.X`)로 재작성 |
| `prompts/questionnaire-extract-requirements.txt` | v2 → v3. 특정 카테고리("사업이해도", "기술적합성") 제거하고 일반 카테고리(기능/성능/인터페이스/데이터/보안/품질/관리 등)로 재작성. RFP 본문 분류 우선 사용 가이드 추가 |
| `prompts/generation-extract-rfp-mandates.txt` | v1 → v2. 특정 표현("PPP 사용자 사례", "사업이해도") 제거하고 추상 패턴 예시로 재작성 |
| `prompts/generation-extract-outline.txt` | v1.2 → v2. "에이전틱 AI", "법령 검색" 등 도메인 예시 제거. 일반화된 트리 구조 placeholder 사용 |
| `OutlineExtractor.java` `reduceOutline()` | `topicsSection`의 RFP-specific 영역 카테고리 제거하고 일반화 |
| `OutlineExtractor.java` `expandSection()` | "추진 전략, 추진 체계 등" 같은 specific 예시 제거하고 일반화된 관점 분류로 재작성 |

코드 인프라(planExpansion, expandWithStrictPlan, dedup 로직, parseTopicForIds 등)는 모두 RFP-agnostic이라 그대로 유지.

### 학습한 generic principles (다음 RFP 처리 시 적용)

1. **planExpansion의 책임 분리**: 단일 LLM 호출이 너무 많은 책임을 가지면 attention budget 경합으로 룰 위반 발생. 다단계 파이프라인 검토 (assignment → topic generation → validation → refinement)
2. **Self-refinement 패턴**: 첫 LLM 호출의 결과를 두 번째 LLM이 generic 체크리스트로 검수하고 fix. 제약이 많은 generation 작업에서 단일 호출의 한계 극복 후보
3. **관점 매트릭스의 일반성**: 의미 겹침 문제는 특정 chapter pair의 문제가 아니라 general pattern. 관점 매트릭스(WHAT/HOW/WHY/...)로 일반화하여 어떤 RFP에도 적용 가능
4. **저배점 underflow 방지**: 평가 배점이 있는 모든 leaf는 weight와 무관하게 최소 정보량 보장 필요 (코드 차원 minimum children 강제 후보)
5. **모델 한계 인식**: 작은 모델은 복잡한 multi-constraint 프롬프트에서 룰 위반이 불가피. 정밀도가 필요한 단계는 더 강한 모델 사용 또는 self-refinement로 보완
6. **단일 RFP 검증의 위험성**: 한 RFP만 보고 시스템을 fit하면 다른 RFP에서 일반화 실패. 검증 RFP는 반드시 다중 도메인

### 후속 task 후보

다음 iteration 또는 시간이 허락할 때 별도 task로 분리:
- **Self-refinement validator 파이프라인** — planExpansion 결과를 generic 체크리스트로 검수·수정
- **다중 도메인 검증 RFP 세트 구축** — 시스템 품질 평가용 fixture (정부/민간/산업 다양화)
- **Section minimum children 보장** — weight와 독립적으로 평가 섹션의 underflow 방지
- **MandatoryItem 사용자 입력 필드** — 사용자가 RFP 도메인 지식을 직접 주입 (시스템이 fit할 필요 없음)

---

## 🚧 다음 작업 시 시작점 (2026-04-10 commit 시점)

**현재 상태**: 모든 Step 1/2/3 작업 + RFP-agnostic cleanup 적용 완료. 컴파일 OK. **단, 알려진 critical 버그 1개**.

### 알려진 critical 버그

**증상**: cleanup 후 재실행 시 5개 leaf 섹션이 빈 껍데기 (sub-item 0개)
- 사용자 보고 사례: II.1, III.3, III.4, VI.1, VI.2

**원인 가설** (확인 필요):
1. cleanup 프롬프트가 너무 추상적 → Haiku가 일부 leaf에 대해 빈 topics 응답 → 빈 plan → free-form expansion fallback → free-form도 빈 응답
2. planExpansion이 일부 leaf를 응답에서 누락 → normalize에서 빈 plan으로 채워짐 → 위와 동일
3. LLM이 leaf key를 typo (예: "II.1" → "II_1") → `parsed.get(leaf.key())`가 null → 빈 plan

가능성 1이 가장 높음. cleanup이 over-shoot.

### 내일 시작할 때 첫 번째 결정

**옵션 A — minimum-children safeguard만 추가** (30분)
- `extractWithFixedTopLevel`과 `reduceOutline`의 expansion 루프 끝에 검증 추가
- children 수 0인 leaf 발견 시 focused single-leaf expansion 호출
- 그래도 빈 채로면 graceful degradation (placeholder 1~2개)
- 효과: 빈 껍데기는 사라지지만 cleanup의 추상화 over-shoot는 유지됨

**옵션 B — cleanup 부분 rollback + safeguard** (1시간) ← 추천
- `generation-plan-expansion.txt`에 multi-domain concrete 예시 1~2개 재추가 (한국 정부 RFP 1개 + 다른 도메인 1개)
- 추상 placeholder 룰은 유지하되 LLM이 anchor할 만한 구체 예시도 같이
- 추가로 옵션 A의 safeguard도 적용
- 효과: LLM이 가이드를 받으면서도 over-fitting은 방지

**옵션 C — selective rollback to Step 3.3 직전** (1.5시간)
- cleanup 커밋만 되돌림 (git revert HEAD)
- v6 상태로 복원
- 그 위에서 IV.3 ↔ V.3 perspective rule만 추가 (RFP-agnostic 형태로)
- 효과: v6의 4.1/5 품질 유지 + 알려진 큰 회귀 1개 추가 fix

**옵션 D — full git reset + 처음부터 다시** (8시간 이상)
- 비추천. 같은 함정 반복 위험.

**옵션 E — 4단계 아키텍처 전환 (Extractor / Planner / Enricher / Validator)** ← **최우선 권고**
- LLM을 구조 결정에서 완전히 제거하고 코드 기반 결정론으로 전환
- 자세한 설계는 아래 "## 옵션 E: 4단계 아키텍처 (Step 4)" 섹션 참고
- Phase A: 즉시 적용 가능한 safeguard + Validator 클래스 (1시간)
- Phase B: Planner 신규 + Enricher 적응 + 파이프라인 재배선 (3~5시간)
- **이 접근이 hill-climbing 패턴을 구조적으로 차단함**

**제 권고**: 옵션 E → 안 되면 옵션 B → 그래도 안 되면 옵션 A로 후퇴

### 옵션 B의 구체적 작업 단계

1. **`generation-plan-expansion.txt` 에 예시 재추가**:
   - 추상 placeholder 룰은 유지
   - "구체 예시 1: 한국 정부 RFP 패턴" 블록 1개 재추가 (II.2 vs II.4 같은 케이스)
   - "구체 예시 2: 다른 도메인" 블록 1개 추가 (전자상거래 또는 물류 RFP 가상 케이스 — LLM이 over-fit하지 않도록 다양성 확보)
   - 총 예시 2개로 LLM에게 anchor + 다양성 동시 제공

2. **`OutlineExtractor.java` 에 minimum-children safeguard**:
   - `extractWithFixedTopLevel` 의 expansion loop 직후
   - `for each (fullPath, children) in expandedChildren`: if children.isEmpty() → call focused single-leaf expansion
   - 신규 메서드 `expandSingleLeafFocused(client, leaf, ...)` — 간단한 prompt: "이 섹션의 title과 description을 보고 children 2~5개 생성"
   - 그래도 빈 채로면 placeholder children: `[OutlineNode(key+".1", title + " 상세 1", "", [])]`

3. **검증**:
   - 컴파일
   - 같은 RFP로 재실행
   - 빈 껍데기 0개 확인
   - II.1, III.3, III.4, VI.1, VI.2 모두 children >= 1 확인

4. **로그 추가** (이미 있으면 강화):
   - planExpansion 후: `Empty plans count: N (leaves: [...])`
   - expansion 후: `Empty children count: N (leaves: [...])`
   - safeguard 트리거: `Triggering focused expansion for empty leaf: ...`

### 코드 위치 참조

- **Empty leaf safeguard 추가 위치**: `OutlineExtractor.java` — `extractWithFixedTopLevel()` 의 `expandedChildren` 루프 끝 (현재 약 line 335)
- **planExpansion 호출 위치**: 같은 메서드 내 line ~322
- **expandSection 무한 fallback 방지**: 새로 추가할 `expandSingleLeafFocused`는 plan.empty()를 강제하여 strict mode로 빠지지 않도록

### 안전망

만약 옵션 B 진행 중 또 다른 회귀가 보이면:
1. 즉시 `git stash`
2. 옵션 C로 전환
3. 그것도 안 되면 옵션 A로 후퇴

옵션 B는 30~60분 시도하고 안 되면 미련 없이 다음 옵션으로 가는 게 ROI가 가장 높음.

### 검증 RFP 다중화 (중기 과제)

이번 사건의 가장 큰 lesson: **단일 RFP로 시스템을 평가하지 말 것.**

다음 큰 작업을 시작하기 전에:
- 검증용 RFP 2~3개 추가 확보 (다른 도메인)
- 각 RFP에 대해 expected 결과 정의 (대략적인 chapter 구조, 요구사항 수, 필수 항목)
- CI 또는 수동 테스트로 시스템 품질을 다중 RFP 기준으로 측정

이것이 갖춰지기 전까지는 어떤 prompt 변경도 over-fitting 위험이 있음.

---

## 옵션 E: 4단계 아키텍처 (Step 4)

이전 4번의 iteration이 모두 hill-climbing에 빠진 근본 원인은 **구조 결정을 LLM에 맡긴 것**.
이 접근은 LLM을 구조 결정에서 완전히 제거하고 결정론 코드로 전환한다.

### 4단계 책임 분할

| 단계 | 책임 | LLM 사용 여부 |
|---|---|---|
| **1. Extractor** | RFP → 요구사항 + mandates 추출 | ✅ LLM (이미 구현됨) |
| **2. Planner** | 요구사항 → leaf 매핑 결정 | ❌ **결정론 코드** (rule-based, 매핑 테이블 사용) |
| **3. Enricher** | leaf 단위 children/description 생성 | ✅ LLM per-section (독립 호출) |
| **4. Validator** | 결과 검증 + reject/retry | ❌ **결정론 코드** (5개 deterministic rule) |

### 핵심 원칙

- **LLM은 추출(extraction)과 보강(enrichment)에만**
- **구조(structure)는 절대 LLM에 맡기지 않음**
- **검증(validation)은 코드 차원, 위반 시 즉시 reject**

### 카테고리→섹션 매핑 테이블

이 접근의 핵심 데이터. 어디서 오느냐가 결정 포인트:

| 옵션 | 방식 | 장단점 |
|---|---|---|
| A | 사용자가 job 생성 시 직접 입력 | 가장 안전, UX 부담 |
| B | RFP 종류별 프리셋 (Korean Gov IT 등) | 중간, 종류 제한 |
| **C** | **1회성 LLM 호출로 derive (그 후 결정론)** | **현실적, 채택** |
| D | RFP 본문에서 명시적 지시 자동 추출 | 가장 깔끔, fallback 필요 |

**채택**: 옵션 C — 1회 LLM 호출로 매핑 결정 후, 코드가 deterministic하게 사용. RFP 종류 무관 동작 + LLM은 분류만 (구조 결정 아님).

### 새 데이터 모델

```java
// 카테고리 → leaf 매핑 (1회 LLM derive)
public record CategoryMapping(
    Map<String, String> categoryToLeafKey  // "SFR" → "III.1"
) {}

// Planner의 출력 — 코드가 만듦, LLM 아님
public record SectionAssignment(
    String leafKey,
    List<String> requirementIds,       // 이 leaf에 할당된 REQ-ID
    List<String> mandatoryItemIds,     // 할당된 의무 항목
    Integer weight                     // 평가 배점 (eval table 매칭)
) {}

// Validator 결과
public record ValidationResult(
    boolean passed,
    List<ValidationViolation> violations
) {}

public record ValidationViolation(
    String ruleName,
    String severity,    // "error" | "warning"
    String message,
    String leafKey      // optional
) {}
```

### 5가지 Validator 규칙

1. **MIN_CHILDREN**: 모든 expanded leaf에 최소 1개 child 존재 (이번 빈 섹션 버그 차단)
2. **REQ_COVERAGE**: 모든 추출 요구사항이 어떤 leaf의 topics에 적어도 1번 등장
3. **REQ_UNIQUENESS**: 동일 REQ-ID가 3개 이상 leaf에 중복되지 않음
4. **MANDATORY_SLOTS**: 모든 mandatory item이 어떤 leaf에 배치
5. **WEIGHT_DISTRIBUTION**: leaf의 children 수가 weight 비율과 ±20% 이내

### 새 파이프라인 (Phase 3 재구성)

```
Phase 1   : 고객 문서 청크 로드                  (기존)
Phase 1.5 : 권장 목차 감지                       (기존)
Phase 1.6 : RfpMandates 추출                     (기존)
Phase 2   : 요구사항 추출                        (기존)
Phase 3   : Outline 구성 (재구성)
  Phase 3a: CategoryMapping derive (1회 LLM)    (NEW)
  Phase 3b: RuleBasedPlanner — leaf별 SectionAssignment 생성 (NEW, 결정론)
  Phase 3c: per-section Enricher — leaf 단위 LLM 호출로 children 생성 (NEW, 기존 expandWithStrictPlan 적응)
  Phase 3d: OutlineValidator — 5개 룰 검사       (NEW, 결정론)
  Phase 3e: 위반 시 focused retry 또는 graceful degradation
```

### 구현 단계 (Phase A → Phase B)

**Phase A — 즉시 적용 가능한 안전 fix (1시간)**:
1. 신규 `OutlineValidator` 클래스 + 5개 rule 메서드
2. 인라인 safeguard: `extractWithFixedTopLevel`의 expansion loop 끝에 빈 leaf 탐지 → focused single-leaf 재확장 → 그래도 빈 채로면 placeholder
3. `WizardAnalysisService.extractOutline()`에 Phase 3.5로 Validator 호출 (warning 로그)
4. **이것만으로 빈 섹션 버그는 해결됨**

**Phase B — 새 아키텍처 구현 (3~5시간)**:
1. 신규 데이터 모델: `CategoryMapping`, `SectionAssignment`, `ValidationResult`, `ValidationViolation`
2. 신규 서비스 `CategoryMappingDeriver` + 프롬프트 `generation-derive-category-mapping.txt` (1회 LLM)
3. 신규 서비스 `RuleBasedPlanner` (결정론, LLM 없음)
4. `expandWithStrictPlan` 시그니처 적응 — `SectionAssignment`를 받도록
5. `WizardAnalysisService.extractOutline()` 새 파이프라인 분기 (feature flag 또는 항상)
6. 기존 `planExpansion`, `dedupRequirementIdsInPlans` 등은 유지 (deprecated 표시)

**Phase B 미구현 시**: Phase A만으로도 현재 빈 섹션 버그 해결되고 v6 수준 품질 유지. Phase B는 architectural cleanup으로 별도 작업 가능.

### 예상 효과

- ✅ **Hill-climbing 차단**: 구조 결정이 결정론이라 LLM attention budget 경합 없음
- ✅ **빈 섹션 차단**: Validator의 MIN_CHILDREN 룰
- ✅ **REQ-ID 중복 차단**: Planner가 1:1 매핑이라 원천 차단
- ✅ **관점 충돌 차단**: 카테고리 매핑이 결정론이라 III↔IV 흔들림 없음
- ✅ **VIII 쓰레기통 차단**: 매핑에 없는 카테고리만 VIII으로
- ✅ **디버깅 가능**: 결정론이라 어떤 leaf에 어떤 req가 갔는지 추적 가능
