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
    List<KeyPoint> keyPoints
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

    public static ProposalStrategy empty() {
        return new ProposalStrategy(List.of());
    }
}
```

### DB 저장

`generation_job` 테이블에 `proposal_strategy JSONB` 컬럼 추가 (Flyway migration).
또는 `requirement_mapping` JSONB 안에 `"strategy"` 키로 함께 저장.

### 목차 생성 연동

`extractWithFixedTopLevel()`의 expansion 프롬프트에 전략 context 추가:
```
## 이 제안서의 핵심 전략 (모든 섹션에서 일관되게 반영)
- KP-1: 법령 환각 제로 — RAG 출처 검증, 전문가 검수
- KP-2: ...
```

### 본문 생성 연동 (후속 작업)

본문 생성 시 각 섹션의 프롬프트에:
- 해당 섹션과 관련된 Key Point / Action Items 전달
- "이 섹션에서 강조해야 할 전략적 메시지" 가이드

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

## 예상 작업량

| 단계 | 작업 | 예상 |
|---|---|---|
| 1 | ProposalStrategy 데이터 모델 + DB migration | 소 |
| 2 | ProposalStrategyDeriver + 프롬프트 작성 | 중 |
| 3 | WizardAnalysisService Phase 2.7 삽입 | 소 |
| 4 | 목차 생성 프롬프트에 전략 context 연동 | 소 |
| 5 | 프론트엔드 전략 표시 UI (선택) | 중 |
| 6 | 본문 생성 연동 (후속) | 별도 작업 |
