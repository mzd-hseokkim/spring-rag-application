package com.example.rag.generation;

public enum GenerationStatus {
    PLANNING,
    GENERATING,
    REVIEWING,
    RENDERING,
    COMPLETE,
    FAILED,
    // 위자드 단계별 상태
    DRAFT,           // Step 1 완료, 아직 분석 전
    ANALYZING,       // Step 2: 목차 추출 중
    MAPPING,         // Step 3: 요구사항 매핑 중
    READY            // 모든 단계 완료, 렌더링 대기
}
