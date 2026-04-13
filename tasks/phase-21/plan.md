# Phase 21 — Embedding 모델 차원 호환성 관리

Embedding 모델 교체 시 모델의 native 차원과 DB 벡터 컬럼 차원 간의 불일치를
일관된 방식으로 관리·처리한다. 현재는 native_dim > column_dim(MRL truncate)
케이스만 지원하고 있으며, 반대 케이스(native_dim < column_dim)와
non-MRL 모델의 truncate 시 품질 저하가 방어되지 않는다.

---

## 배경

Phase 20 이전까지 embedding 모델은 Azure `text-embedding-3-small`(1536d)로
고정되어 있어 차원 불일치 이슈가 없었다. 최근 기본 모델을 Ollama
`qwen3-embedding:4b`(native 2560d)로 교체하면서:

- `document_chunk.embedding`은 `vector(1536)` 스키마 유지
- qwen3는 MRL(Matryoshka Representation Learning) 학습 모델이므로
  앞쪽 1536차원으로 truncate + L2 재정규화해도 품질 저하 1~2%p 수준
- `TruncatingEmbeddingModel` 데코레이터를 `ModelClientFactory`에 도입하여
  `qwen3-embedding*` 모델에 한해 1536 truncate를 적용

이 임시 구현은 다음 시나리오를 방어하지 못한다.

1. **native_dim < column_dim (예: 1024d 모델 등록)**
   런타임 시 pgvector insert 실패 또는 차원 불일치 에러
2. **non-MRL 모델의 truncate**
   MRL 가정 없이 잘라내면 의미가 파괴되어 검색 품질 급락
3. **모델별 하드코딩된 분기**
   `modelId.startsWith("qwen3-embedding")` 방식은 확장성이 없음

---

## 목표

- 모델의 native 차원과 MRL 지원 여부를 메타데이터로 **명시적으로** 관리한다
- 팩토리는 메타데이터를 기반으로 어댑터를 선택하므로 신규 모델 추가 시 코드 변경이 필요 없다
- 모든 케이스에서 cosine similarity 수학적 동등성 또는 명확한 에러를 보장한다

---

## 처리 전략 (수학적 근거)

| native_dim | column_dim | supports_mrl | 처리 | 안전성 |
|------------|------------|--------------|------|--------|
| = column | 1536 | - | pass-through | ✅ 완벽 |
| > column | 1536 | true | 앞 N차원 truncate + L2 재정규화 | ✅ MRL 학습 가정으로 1~2%p 손실 |
| > column | 1536 | false | **등록 거부** (default) 또는 경고 후 truncate (옵션) | ⚠️ 큰 품질 저하 위험 |
| < column | 1536 | - | 뒤쪽 0-padding | ✅ cosine 수학적 동등성 유지 |
| ≪ column (< 절반) | 1536 | - | 등록 거부 | ❌ 저장 낭비 심각 |

### 0-padding 동등성 증명

쿼리 `q ∈ ℝⁿ`, 문서 `d ∈ ℝⁿ` (native n < column m)에 대해
`q' = [q, 0_{m-n}]`, `d' = [d, 0_{m-n}]` 라 할 때:

- `q'·d' = Σᵢ qᵢdᵢ + 0 = q·d`
- `||q'|| = √(||q||² + 0) = ||q||`, `||d'||` 동일
- ∴ `cos(q', d') = cos(q, d)` ✅

즉, 일관된 0-padding은 검색 품질에 **정보 손실이 없다**. 단 인덱스
효율·저장 공간 측면에서 낭비가 발생하므로 운영상의 비효율만 존재한다.

---

## 단계별 구현 계획

### Step 1. `llm_model` 메타데이터 확장 (Flyway V36)

**DDL**:
```sql
ALTER TABLE llm_model
    ADD COLUMN native_dim     INT,
    ADD COLUMN supports_mrl   BOOLEAN NOT NULL DEFAULT false;

-- 기존 EMBEDDING 모델 시드
UPDATE llm_model
SET native_dim = 2560, supports_mrl = true
WHERE provider = 'OLLAMA' AND model_id = 'qwen3-embedding:4b';
```

- `native_dim`: 모델의 native 출력 차원. NULL 허용 (자동 탐지 대상)
- `supports_mrl`: Matryoshka 학습 여부. 보수적으로 default false

**엔티티/레코드 업데이트**: `LlmModel` 필드 추가, Repository/매퍼 조정.

**검증**: V36 적용 후 `SELECT native_dim, supports_mrl FROM llm_model` 확인

---

### Step 2. 설정 상수 및 차원 정책

**`application.yml`**:
```yaml
app:
  embedding:
    column-dim: 1536   # document_chunk.embedding 의 vector(N) 차원
    reject-shrink-ratio: 0.5  # native < column * ratio 이면 등록 거부
    allow-non-mrl-truncate: false  # non-MRL 모델의 truncate 허용 여부 (기본 거부)
```

**`EmbeddingProperties`** (`@ConfigurationProperties("app.embedding")`):
- `columnDim: int`
- `rejectShrinkRatio: double`
- `allowNonMrlTruncate: boolean`

---

### Step 3. 어댑터 구현

**위치**: `com.example.rag.model.embedding.*`

1. **`TruncatingEmbeddingModel`** (기존 이동/유지)
   - native > column + MRL 모델용
   - 이미 구현됨. 패키지만 정리
2. **`ZeroPaddingEmbeddingModel`** (신규)
   - native < column 모델용
   - `call()` / `embed(Document)` 결과를 `float[column]`로 확장 (뒤쪽 0)
   - L2 정규화는 **불필요** (norm 불변, 위 증명 참고)
   - `dimensions()` 오버라이드: `targetDim` 반환

**ZeroPaddingEmbeddingModel 핵심 로직**:
```java
private float[] pad(float[] src) {
    if (src.length >= targetDim) return src;  // 방어적
    float[] out = new float[targetDim];
    System.arraycopy(src, 0, out, 0, src.length);
    return out;  // 나머지 인덱스는 0.0f (default)
}
```

---

### Step 4. 팩토리 분기 로직 재설계

**`ModelClientFactory.createEmbeddingModel(LlmModel)`**:

```java
EmbeddingModel base = createBaseEmbeddingModel(model);
int columnDim = props.getColumnDim();
int nativeDim = resolveNativeDim(model, base);  // NULL이면 1회 probe

if (nativeDim == columnDim) return base;

if (nativeDim > columnDim) {
    if (Boolean.TRUE.equals(model.getSupportsMrl())) {
        return new TruncatingEmbeddingModel(base, columnDim);
    }
    if (props.isAllowNonMrlTruncate()) {
        log.warn("Non-MRL truncate applied to {}: quality loss expected", model.getModelId());
        return new TruncatingEmbeddingModel(base, columnDim);
    }
    throw new RagException(
        "Model %s native_dim=%d > column_dim=%d but supports_mrl=false"
            .formatted(model.getModelId(), nativeDim, columnDim));
}

// nativeDim < columnDim
if (nativeDim < columnDim * props.getRejectShrinkRatio()) {
    throw new RagException(
        "Model %s native_dim=%d is less than %.0f%% of column_dim=%d — rejected"
            .formatted(model.getModelId(), nativeDim, props.getRejectShrinkRatio() * 100, columnDim));
}
log.warn("Zero-padding applied to {}: storage waste (native={}, column={})",
        model.getModelId(), nativeDim, columnDim);
return new ZeroPaddingEmbeddingModel(base, columnDim);
```

**`resolveNativeDim`**:
- `model.getNativeDim()`이 NOT NULL이면 그대로 사용
- NULL이면 `base.embed("probe")`로 1회 측정 → `llm_model.native_dim`에 UPDATE

---

### Step 5. 모델 등록 API 검증 강화

`LlmModelService` (또는 `ModelAdminController`)에 EMBEDDING 모델 등록 시:

- `native_dim` 미입력 시 probe 실행 (Step 4와 동일)
- 위 정책에 따라 등록 거부 조건을 사전 검증 후 예외 발생
- `is_default = true` 설정 전에 호환성 확인

---

### Step 6. 테스트

**단위 테스트**:
- `TruncatingEmbeddingModelTest` — 2560 → 1536 truncate + 정규화 검증
- `ZeroPaddingEmbeddingModelTest`
  - padding 후 cosine similarity가 원본과 일치함을 확인
  - dim < target일 때만 padding, 이미 같으면 pass
- `ModelClientFactoryTest`
  - 5가지 분기 케이스 모두 커버 (mock `LlmModel`)

**통합 테스트**:
- native_dim NULL 모델 probe 후 DB 업데이트 확인
- non-MRL 모델 + truncate 거부 시 명확한 에러 메시지

---

### Step 7. 관리자 UI (선택적, Phase 21.5)

- 모델 등록/편집 화면에 `native_dim`, `supports_mrl` 필드 노출
- EMBEDDING 모델 선택 시 column_dim과의 호환성을 실시간 표시
  - ✅ "Pass-through (1536d)"
  - ✅ "MRL Truncate (2560 → 1536, ~1-2% 손실)"
  - ⚠️ "Zero-padding (1024 → 1536, 저장 낭비 33%)"
  - ❌ "Rejected: non-MRL model cannot truncate"

---

## 비목표 (Out of Scope)

- **모델별 벡터 컬럼 분리**: 서로 다른 dim의 embedding을 동시 운영하려면
  `document_chunk_1536`, `document_chunk_1024` 등 테이블 분리가 필요하나,
  현 단계에서는 "하나의 기본 모델 + 호환 어댑터"로 충분
- **Embedding space 호환성**: dim이 맞아도 서로 다른 모델의 벡터는
  같은 공간이 아니므로 **모델 교체 시 재인덱싱 필수**. 이 문제는 본 Phase
  범위 밖이며 모델 변경 플로우(운영 문서)에서 별도로 다룬다
- **동적 column_dim 변경**: 런타임에 `document_chunk.embedding` 컬럼 차원을
  변경하는 기능은 제공하지 않는다 (스키마 변경 + 전량 재인덱싱 필요)

---

## 수용 기준 (Definition of Done)

- [ ] V36 마이그레이션 적용 후 `llm_model`에 `native_dim`, `supports_mrl` 컬럼 존재
- [ ] qwen3-embedding:4b 시드가 `native_dim=2560, supports_mrl=true`로 업데이트됨
- [ ] `application.yml`의 `app.embedding.column-dim=1536` 구성이 적용됨
- [ ] `ZeroPaddingEmbeddingModel` 단위 테스트에서 cosine similarity 불변성 확인
- [ ] 5가지 분기 (pass / MRL truncate / non-MRL 거부 / zero-pad / 극소 dim 거부) 단위 테스트 통과
- [ ] 신규 EMBEDDING 모델 등록 시 `native_dim`이 NULL이어도 probe 후 자동 기록
- [ ] 기존 qwen3-embedding:4b 기반 검색 동작이 Phase 20과 동일 (회귀 없음)
