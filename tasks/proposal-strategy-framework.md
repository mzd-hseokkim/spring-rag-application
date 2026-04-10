# 제안 전략 프레임워크 (Proposal Strategy Framework) 생성 단계 추가

## 개요

목차 생성 파이프라인에 "제안 전략 프레임워크" 도출 단계를 추가한다.
RFP 분석 결과(요구사항, 의무항목, 배점표)를 기반으로 **핵심 Key Point N개 + Action Items M개**를 생성하고,
이를 목차 구성 및 본문 작성의 일관된 메시지 축으로 활용한다.

## 배경 및 목적

### 현재 파이프라인 (commit 40e682b 기준)

```
Phase 1    — 고객문서 청크 로드
Phase 1.5  — 권장 목차 감지
Phase 1.6  — RFP 의무항목 + 배점표 추출
Phase 2    — 요구사항 추출 (캐시 지원)
Phase 2.5  — 가중치 fallback
Phase 3    — 목차 생성 (extractWithFixedTopLevel 또는 map-reduce)
Phase 3.5  — 의무항목 커버리지 검증
Phase 3.6  — OutlineValidator 5개 룰
```

### 문제

- 현재 목차는 요구사항을 "빠짐없이 배치"하는 데 집중. **전략적 메시지**가 없음.
- 본문 작성 시 각 섹션이 독립적으로 서술되어, 제안서 전체를 관통하는 일관된 스토리가 부족.
- 평가위원이 "이 업체의 핵심 강점이 뭔지" 한눈에 파악하기 어려움.

### 목표

- RFP 분석 후 **핵심 Key Point 5~8개** 자동 도출
- 각 Key Point에 **Action Items 2~4개** 매핑
- 이 전략 프레임워크를 목차 구조와 본문 생성에 반영

## 제안 전략 프레임워크 구조

```json
{
  "keyPoints": [
    {
      "id": "KP-1",
      "title": "법령 환각 제로",
      "description": "생성형 AI의 환각(hallucination)을 원천 차단하여 법령 검색 신뢰성 확보",
      "rationale": "RFP p.4 '환각에 의해 존재하지 않는 법령이 검색되는 등 혼란 초래' 직접 명시",
      "evaluationWeight": "기술성 40점 중 핵심",
      "actionItems": [
        {
          "id": "AI-1-1",
          "title": "RAG 기반 출처 검증 파이프라인 구축",
          "relatedRequirements": ["SFR-001", "SFR-003"],
          "outlineSections": ["III.1.1", "IV.1.1"]
        },
        {
          "id": "AI-1-2",
          "title": "법률 전문가 검수 프로세스 설계",
          "relatedRequirements": ["SFR-005"],
          "outlineSections": ["III.1.4"]
        }
      ]
    }
  ]
}
```

## 파이프라인 삽입 위치

### 옵션 A — Phase 2.7 (목차 생성 전) ← 추천

```
Phase 2    — 요구사항 추출
Phase 2.5  — 가중치 fallback
Phase 2.7  — ★ 제안 전략 프레임워크 도출 ★
Phase 3    — 목차 생성 (전략 프레임워크를 context로 전달)
```

**장점**: 목차 구조 자체가 전략에 맞게 정렬. Key Point가 outline 생성의 guide 역할.
**단점**: 목차 생성에 의존성 추가. 전략 도출 실패 시 fallback 필요.

### 옵션 B — Phase 3.7 (목차 생성 후)

```
Phase 3.6  — OutlineValidator
Phase 3.7  — ★ 제안 전략 프레임워크 도출 ★
```

**장점**: 기존 파이프라인 변경 최소. 목차가 이미 있으므로 Key Point → 섹션 매핑 용이.
**단점**: 전략이 목차 구조에 반영되지 않음. 본문 작성 시에만 활용.

### 추천: 옵션 A

전략이 목차를 형성해야 제안서 품질이 높아짐. 목차 생성 전에 전략을 먼저 잡고,
`extractWithFixedTopLevel()`의 expansion 시 전략 context를 프롬프트에 포함.

## 입력 데이터

| 데이터 | 출처 | 용도 |
|---|---|---|
| 요구사항 목록 | Phase 2 RequirementExtractor | Key Point 후보 도출 |
| RFP 의무항목 | Phase 1.6 RfpMandateExtractor | 필수 포함 사항 식별 |
| 평가 배점표 | Phase 1.6 RfpMandateExtractor | 배점 비중으로 Key Point 우선순위 결정 |
| 고객문서 청크 | Phase 1 | RFP 맥락 파악 (사업 목적, 추진 배경 등) |
| 권장 목차 | Phase 1.5 | 제안서 구조 제약 |

## 구현 설계 (초안)

### 새 클래스: `ProposalStrategyDeriver`

```
com.example.rag.generation.workflow.ProposalStrategyDeriver
```

- `derive(requirements, rfpMandates, customerChunks, userInput) → ProposalStrategy`
- LLM 1회 호출: 요구사항 요약 + RFP 핵심 맥락 → Key Points + Action Items
- 프롬프트: `generation-derive-proposal-strategy.txt`

### 데이터 모델: `ProposalStrategy`

```java
public record ProposalStrategy(
    List<KeyPoint> keyPoints,
    Map<String, SectionRole> sectionRoles  // "III.1" → STRATEGY_IMPL
) {
    public record KeyPoint(
        String id,
        String title,
        String description,
        String rationale,
        List<ActionItem> actionItems
    ) {}

    public record ActionItem(
        String id,
        String title,
        List<String> relatedRequirementIds
    ) {}

    public enum SectionRole {
        STRATEGY_NARRATIVE,  // 전략 직접 서술 — KP를 chapter의 핵심 골격으로 사용
        STRATEGY_IMPL,       // 전략 구현 — "KP를 이렇게 구현한다" 서술
        CHECKLIST,           // 체크리스트 대응 — RFP 요구사항에 빠짐없이 답변
        FACTUAL,             // 사실 기반 — 회사 정보, 실적, 조직도
        STANDARD_PROCESS     // 표준 프로세스 — 관리/교육/인수인계 방법론
    }

    public static ProposalStrategy empty() {
        return new ProposalStrategy(List.of(), Map.of());
    }
}
```

### 섹션 성격 분류 (SectionRole)

제안서의 모든 섹션이 전략과 연결되는 것은 아니다.
전략 context 주입은 `STRATEGY_NARRATIVE`와 `STRATEGY_IMPL` 섹션에만 적용하고,
나머지는 기존 방식(요구사항 기반)으로 서술한다.

| 성격 | 서술 방식 | 전략 연동 | 해당 섹션 (권장 목차 기준) |
|---|---|---|---|
| **STRATEGY_NARRATIVE** | KP/AI를 직접 서술, 차별화 강조 | ✅ 필수 | II.2 추진전략, II.4 사업추진 방법론 |
| **STRATEGY_IMPL** | "이 KP를 이렇게 구현한다" | ✅ 관련 KP만 | III.1~4 수행계획, IV.1 적용기술 |
| **CHECKLIST** | RFP 요구사항 빠짐없이 답변 | ❌ | IV.3 보안, IV.4 제약, IV.5 테스트 |
| **FACTUAL** | 회사 정보/실적/조직도 | ❌ | I.1 일반현황, I.2 조직 및 인원 |
| **STANDARD_PROCESS** | 표준 관리/교육/인수인계 방법론 | ❌ | V.1~4 프로젝트 관리, VI.1~4 지원, VII, VIII |

**비율**: 전략 연동 ~30% (NARRATIVE+IMPL), 체크리스트/기본기 ~70%.

#### CategoryMappingDeriver `role`과의 관계

기존 `CategoryMappingDeriver`가 부여하는 role (WHY, WHAT, HOW-tech, HOW-method, CTRL-tech, CTRL-mgmt, MGMT, OPS, MISC)과 SectionRole은 유사한 개념.
통합 가능하지만, 역할이 다름:
- `role`: 어떤 **카테고리의 요구사항**을 배치할지 결정 (Planner 입력)
- `SectionRole`: 해당 섹션의 **서술 방식**을 결정 (프롬프트 출력 스타일)

구현 시 `role → SectionRole` 매핑 테이블로 연결:

```
WHY          → FACTUAL 또는 STRATEGY_NARRATIVE
WHAT         → STRATEGY_IMPL
HOW-tech     → STRATEGY_IMPL
HOW-method   → STRATEGY_NARRATIVE
CTRL-tech    → CHECKLIST
CTRL-mgmt    → CHECKLIST
MGMT         → STANDARD_PROCESS
OPS          → STANDARD_PROCESS
MISC         → STANDARD_PROCESS
```

또는 `ProposalStrategyDeriver`가 outline의 leaf 목록을 보고 직접 SectionRole을 분류 (LLM 1회 호출에 KP 도출 + role 분류를 함께 수행).

### DB 저장

`generation_job` 테이블에 `proposal_strategy JSONB` 컬럼 추가 (Flyway migration).
또는 `requirement_mapping` JSONB 안에 `"strategy"` 키로 함께 저장.

### Downstream 데이터 흐름

```
Phase 2.7  ProposalStrategyDeriver
    ↓ ProposalStrategy (KP + AI) — generation_job.proposal_strategy에 persist
    │
    ├─→ [연동 1] Phase 3 목차 생성
    │     ├─ consolidateTopics(): 전략 context로 topic 통합 우선순위 결정
    │     └─ expandWithStrictPlan(): description에 관련 KP 참조 반영
    │
    ├─→ [연동 2] II.2 추진전략 섹션 자동 구성
    │     └─ Key Points가 곧 II.2의 골격 (children = KP 제목)
    │
    ├─→ [연동 3] Step 3 요구사항 매핑
    │     └─ KP→AI→요구사항 ID 매핑으로 섹션-전략 추적성 확보
    │
    └─→ [연동 4] Step 4 본문 생성 (후속 작업)
          └─ 각 섹션 프롬프트에 관련 KP/AI 주입
```

### 연동 1: 목차 생성 (Phase 3)

**consolidateTopics()에 전략 context 전달**

현재:
```
"다음 42개 항목을 최대 8개 핵심 주제로 통합하세요."
```

개선:
```
"다음 42개 항목을 최대 8개 핵심 주제로 통합하세요.

## 이 제안서의 핵심 전략 (통합 시 우선순위 참고)
- KP-1: 법령 환각 제로 (관련: SFR-001, SFR-003, SFR-005)
- KP-2: 검색 응답 2초 이내 (관련: NFR-001, NFR-003)
→ 관련 Key Point의 Action Items가 통합 주제에 반영되어야 합니다."
```

**구현 위치**: `OutlineExtractor.consolidateTopics()` 메서드에 `ProposalStrategy` 파라미터 추가.
전략이 없으면(empty) 기존 프롬프트 그대로 사용.

**SectionRole 기반 분기**: `STRATEGY_IMPL` 섹션만 전략 context 주입.
`CHECKLIST`/`FACTUAL`/`STANDARD_PROCESS` 섹션은 전략 context 없이 기존 방식.

**expandWithStrictPlan()에 전략 context 전달**

각 leaf의 description 생성 시:
```
"이 섹션은 핵심 전략 'KP-1: 법령 환각 제로'의 Action Item
'RAG 기반 출처 검증 파이프라인 구축'과 직접 관련됩니다.
description에 이 전략적 메시지를 반영하세요."
```

**구현**: `expandWithStrictPlan()` 프롬프트에 `## 관련 핵심 전략` 섹션 조건부 추가.
KP의 `relatedRequirementIds`와 해당 leaf의 topics(요구사항 ID 포함)를 매칭하여 관련 KP만 주입.

### 연동 2: II.2 추진전략 섹션 자동 구성

**II.2의 children을 Key Points로 직접 매핑**

현재 II.2는 RuleBasedPlanner가 일반적으로 children을 생성.
전략이 있으면 II.2의 children을 Key Points로 **오버라이드**:

```
II.2.1. 법령 환각 제로 전략 (KP-1)
II.2.2. 검색 응답 2초 이내 달성 전략 (KP-2)
II.2.3. ...
```

**구현**: `extractWithFixedTopLevel()`에서 II.2(또는 추진전략에 해당하는 leaf)를 감지하고,
해당 leaf의 expansion을 전략 기반으로 전환. `expandSection()` 호출 전에 plan을 KP 기반으로 교체.

### 연동 3: 요구사항 매핑 (Step 3)

**전략 기반 매핑 추적성**

`generation_job.requirement_mapping`에 `"strategy"` 키 추가:
```json
{
  "requirements": [...],
  "mapping": {...},
  "rfpMandates": {...},
  "strategy": {
    "keyPoints": [
      {
        "id": "KP-1",
        "title": "법령 환각 제로",
        "actionItems": [
          {"id": "AI-1-1", "relatedRequirementIds": ["SFR-001", "SFR-003"]},
          ...
        ],
        "outlineSections": ["III.1.1", "IV.1.1"]  ← 매핑 후 채워짐
      }
    ]
  }
}
```

`RequirementMapper.map()` 완료 후, 각 KP의 `relatedRequirementIds`가 어느 섹션에 매핑됐는지
역추적하여 `outlineSections` 필드를 채움. 이 정보는 본문 생성 시 "이 섹션이 어떤 전략과 관련되는지" 참조에 사용.

### 연동 4: 본문 생성 (Step 4, 후속 작업)

각 섹션의 본문 프롬프트에 관련 전략 정보 주입:

```
## 이 섹션과 관련된 핵심 전략
- KP-1: 법령 환각 제로
  - Action Item: RAG 기반 출처 검증 파이프라인 구축
  - 이 섹션에서 강조할 점: 검색 결과의 법적 정확성을 어떻게 보장하는지 구체적으로 서술

→ 이 전략적 메시지를 자연스럽게 반영하여 작성하세요.
  직접 "KP-1" 같은 코드를 본문에 노출하지 마세요.
```

**구현**: 본문 생성 프롬프트에 `{strategyContext}` 변수 추가.
섹션 key → KP 매핑 (연동 3의 `outlineSections`)을 통해 관련 KP만 필터링.

## 프롬프트 설계 방향

### `generation-derive-proposal-strategy.txt` 핵심 지시

1. RFP의 사업 목적, 추진 배경, 핵심 과제를 파악
2. 평가 배점 비중이 높은 영역을 우선적으로 Key Point로 선정
3. 각 Key Point는:
   - 평가위원이 "이 업체의 강점"으로 인식할 수 있는 차별화 포인트
   - RFP 본문의 구체적 근거(페이지, 절 번호)와 연결
   - 실현 가능한 Action Items로 뒷받침
4. Key Point 5~8개, 각 Key Point당 Action Items 2~4개
5. Action Items는 해당 요구사항 ID와 매핑

## 검증 기준

- [ ] Key Points가 RFP의 핵심 과제를 포괄하는가
- [ ] Action Items가 실제 요구사항에 매핑되는가
- [ ] 목차의 주요 섹션(II.전략, III.수행계획)이 Key Points를 반영하는가
- [ ] 본문 생성 시 Key Points가 일관되게 참조되는가

## 의존성 및 제약

- Phase 1.6 rfpMandates가 정상 추출되어야 배점 기반 우선순위 결정 가능 (현재 totalScore=None)
- evaluationWeights가 비어있으면 요구사항 importance(상/중/하) 기반 fallback
- 전략 도출 실패 시 목차 생성은 기존 로직으로 정상 진행 (graceful degradation)

## 구현 순서 및 예상 작업량

| 순서 | 작업 | 영향 범위 | 예상 |
|---|---|---|---|
| 1 | `ProposalStrategy` record + DB migration (컬럼 추가) | 데이터 모델 | 소 |
| 2 | `ProposalStrategyDeriver` + 프롬프트 작성 | 새 클래스 | 중 |
| 3 | `WizardAnalysisService` Phase 2.7 삽입 + persist | 파이프라인 | 소 |
| 4 | **[연동 1]** `consolidateTopics()` + `expandWithStrictPlan()`에 전략 context 주입 | OutlineExtractor | 중 |
| 5 | **[연동 2]** II.2 추진전략 children을 KP로 오버라이드 | OutlineExtractor | 소 |
| 6 | **[연동 3]** `RequirementMapper`에서 KP→섹션 역매핑 | RequirementMapper | 소 |
| 7 | 프론트엔드 전략 표시 UI (선택) | frontend | 중 |
| 8 | **[연동 4]** 본문 생성 프롬프트에 전략 context 주입 | 별도 작업 | 중 |

### 최소 MVP (1~4)

1~4까지 구현하면 전략이 목차에 반영됨. 5~6은 추가 개선, 7~8은 후속 작업.
