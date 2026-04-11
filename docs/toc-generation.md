# 목차 생성 (Table of Contents Generation)

## 개요

RFP(제안요청서) 분석 결과를 기반으로 제안서 목차를 자동 생성하는 파이프라인.
권장 목차가 있으면 그 구조를 존중하고, 요구사항을 적절한 섹션에 배치하며,
각 섹션의 서술 관점(What vs How)을 분리하여 제안서 품질을 확보한다.

## 파이프라인 흐름

```
Phase 1    고객문서 청크 로드
Phase 1.5  권장 목차 감지 (LLM)
Phase 1.6  RFP 의무항목 + 배점표 추출 (LLM)
Phase 2    요구사항 추출 (LLM, 캐시 지원)
Phase 2.5  가중치 fallback (importance → weight)
Phase 3    목차 생성
Phase 3.5  의무항목 커버리지 검증
Phase 3.6  OutlineValidator (5개 결정론 룰)
```

### Phase 3 상세

권장 목차가 있으면 `extractWithFixedTopLevel()` 경로로 진행:

```
1. parseOutline(recommendedOutline)
   → 권장 목차 JSON을 OutlineNode 트리로 파싱. 원본 번호 체계(로마숫자 등) 유지.

2. collectLeaves()
   → 트리에서 children이 없는 leaf 노드 수집. 이 leaf들이 expansion 대상.

3. CategoryMappingDeriver (LLM 1회)
   → 각 leaf에 role 부여 (WHY, WHAT, HOW-tech, HOW-method, CTRL-tech, CTRL-mgmt, MGMT, OPS, MISC)
   → 요구사항 카테고리를 leaf에 1:N 매핑

4. RuleBasedPlanner (결정론)
   → 카테고리 매핑 기반으로 요구사항을 leaf에 분배 (round-robin + soft cap 10)
   → 의무항목(MAND) 배치 (WHY/FACTUAL role 제외)

5. 각 leaf에 대해 expansion:
   a. topics > 8 → consolidateTopics(): 유사 주제 통합
   b. WHAT role + topics ≤ 8 → rewriteTopicsForWhatRole(): 기술→업무 언어 변환
   c. expandWithStrictPlan(): skeleton 구축 + LLM description 생성
   d. HOW-tech/CTRL-tech → grandchildren(L5) 자동 생성

6. 후처리:
   a. filterMetaNodes(): LLM 메타 텍스트 제거
   b. deduplicateTitles(): 유사 제목 통합 (80%+ 키워드 겹침)
   c. cleanTitleArtifacts(): (숫자.숫자) 패턴 제거
```

## 핵심 설계 원칙

### 1. RFP-Agnostic

모든 로직은 특정 RFP의 구조(I=일반현황, III=수행계획 등)에 의존하지 않는다.

- **role 기반 판단**: 섹션의 성격은 `CategoryMappingDeriver`가 leaf title을 보고 LLM으로 결정.
  코드에 챕터 번호(I, II, III...)나 특정 섹션명을 하드코딩하지 않는다.
- **title 기반 scope**: `getPerspective(role, title)`에서 role은 서사 흐름 위치를,
  title은 해당 위치 내 구체적 범위를 결정. 둘 다 동적.
- **프롬프트 예시도 도메인 중립**: 변환 예시에 특정 사업 도메인을 넣지 않는다.

### 2. 관점 분리 (Perspective Separation)

같은 요구사항이라도 섹션의 role에 따라 서술 관점이 달라야 한다.

| Role | 관점 | 서술 내용 | 금지 |
|------|------|----------|------|
| WHAT | 업무/사용자 | 달성 목표, 서비스 결과물, 목표 수치 | 기술명, 프레임워크명, 알고리즘명 |
| HOW-tech | 기술/구현 | 아키텍처, 프레임워크, 구현 방법 | 업무 기능 설명 반복 |
| HOW-method | 방법론 | 수행 접근법 (균형있게) | 한 영역 편중, 기술 상세 |
| WHY | 배경/전략 | 사업 배경, 추진 필요성 | 기술 제안, 구현 방법론 |
| CTRL-tech | 기술 통제 | 보안/테스트 구현 | 관리 프로세스 |
| CTRL-mgmt | 관리 통제 | 보안/품질 운영 관리 | 기술 구현 상세 |
| MGMT | 관리 | title 범위의 관리 활동 | 형제 섹션 내용 반복 |
| OPS | 운영/지원 | title 범위의 운영 활동 | 형제 섹션 내용 반복 |

### 3. 카테고리 매핑 규칙

- **전용 leaf 우선**: 카테고리명과 일치하는 전용 leaf(title에 키워드 포함)가 있으면 우선 매핑.
- **WHAT↔HOW-tech 분배 허용**: 같은 카테고리를 "무엇을"(WHAT)과 "어떻게"(HOW-tech) 양쪽에 매핑하는 것은 허용하고 권장. 이것이 관점 분리를 만든다.
- **범용 leaf 보호**: 전략, 추진체계, 방법론 같은 범용 leaf에 특정 카테고리(보안 등)가 몰리는 것은 금지.
- **"요구사항" title = WHAT**: title에 "요구사항"이 포함된 leaf는 반드시 WHAT role. 성능/데이터/인터페이스 요구사항 모두 해당.

### 4. 중복 방지

- **sibling awareness**: expansion 시 같은 parent의 형제 섹션 목록을 프롬프트에 주입.
  "V.1 일정관리, V.2 품질관리가 다루므로 V.4에 넣지 마세요" 같은 context.
- **topic ledger**: 이미 확장된 섹션의 주제를 누적하여 후속 섹션에서 중복 방지.
- **deduplicateTitles()**: 같은 parent 아래 80%+ 키워드 겹침 제목을 자동 통합.

### 5. 볼륨 제어

- **MAX_CHILDREN_PER_SECTION = 8**: 한 섹션의 직접 children은 최대 8개.
  초과 시 `consolidateTopics()`로 유사 주제를 통합.
- **WHAT role rewrite**: topics ≤ 8이어도 WHAT role이면 기술 용어를 업무 언어로 재표현.
- **L5(grandchild)**: HOW-tech/CTRL-tech role에서만 자동 생성. WHAT/MGMT/OPS에서는 L4까지만.

### 6. 후처리 방어선

LLM이 프롬프트를 무시할 경우의 최후 방어:

- **filterMetaNodes()**: "재정리", "이관 항목", "형제 섹션", "검토 필요" 등 LLM 메타 텍스트가 title에 포함된 노드 제거.
- **cleanTitleArtifacts()**: `(숫자.숫자)` 같은 내부 참조 아티팩트 제거.
- **cleanMetaDescription()**: "이관하여 관리하며", "슬라이드" 등 description 내 메타 텍스트 치환/제거.
- **stripTechTermsFromDescription()**: WHAT role description에서 영문 기술명 자동 제거 (REQ/SFR/NFR ID는 보존).

## 주요 클래스

| 클래스 | 역할 |
|---|---|
| `WizardAnalysisService` | 파이프라인 오케스트레이션 (Phase 1~3.6) |
| `OutlineExtractor` | 목차 생성 핵심 로직 (detect, extract, expand, 후처리) |
| `CategoryMappingDeriver` | LLM 1회 호출로 카테고리→leaf 매핑 + role 부여 |
| `RuleBasedPlanner` | 결정론 요구사항 분배 (round-robin, soft cap) |
| `OutlineValidator` | 5개 결정론 룰 검증 (MIN_CHILDREN, REQ_COVERAGE 등) |
| `RequirementExtractor` | 요구사항 추출 (batch parallel, 캐시 지원) |
| `RfpMandateExtractor` | 의무항목 + 배점표 추출 |
| `RequirementCacheService` | L1(Redis) + L2(PostgreSQL) 2단계 캐시 |

## 프롬프트 파일

| 파일 | 용도 |
|---|---|
| `generation-derive-category-mapping.txt` | 카테고리→leaf 매핑 + role 부여 |
| `generation-extract-outline.txt` | 목차 구조 생성 (direct/map-reduce) |
| `generation-extract-rfp-mandates.txt` | 의무항목 + 배점표 추출 |
| `questionnaire-extract-requirements.txt` | 요구사항 추출 |

## 캐시 구조

요구사항 추출 결과는 문서 hash 기반으로 캐시:

- **L1 (Redis)**: `requirements:extracted:{hash}` — TTL 기반, 빠른 조회
- **L2 (PostgreSQL)**: `requirement_cache` 테이블 — 영구 저장

프롬프트 변경 시 **반드시 L1 + L2 모두 삭제**해야 새 프롬프트가 적용됨.

```bash
docker exec rag-redis redis-cli DEL requirements:extracted:{hash}
docker exec rag-postgres psql -U raguser -d ragdb -c "DELETE FROM requirement_cache;"
```

## 설정

| 설정 | 위치 | 값 |
|---|---|---|
| Anthropic max_tokens | `llm_model` 테이블 | 32768 |
| Anthropic timeout | `ModelClientFactory.java` | 10분 |
| 로그 파일 | `application.yml` | `../logs/spring-rag-application.log` (daily rolling) |
| 요구사항 batch 크기 | `RequirementExtractor.java` | 50,000 chars |
| 병렬 처리 | `RequirementExtractor.java` | MAX_PARALLEL = 3 |
| Children 상한 | `OutlineExtractor.java` | MAX_CHILDREN_PER_SECTION = 8 |
| 요구사항 soft cap | `RuleBasedPlanner.java` | LEAF_REQ_SOFT_CAP = 10 |
