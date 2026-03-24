package com.example.rag.questionnaire.workflow;

import java.util.List;
import java.util.stream.Collectors;

public record GapMatrix(
        List<GapEntry> entries,
        String summary
) {
    public record GapEntry(
            Requirement requirement,
            String matchStatus,
            String proposalContent,
            String gapDescription
    ) {}

    /**
     * 마크다운에서 갭 매트릭스를 복원한다. 캐시 히트 시 페르소나별 재정렬에 사용.
     * 파싱 실패 시 null을 반환하며, 호출측에서 원본 마크다운을 그대로 사용한다.
     */
    public static GapMatrix fromMarkdown(String markdown) {
        if (markdown == null || !markdown.contains("## 갭 매트릭스")) {
            return null;
        }
        try {
            List<GapEntry> entries = new java.util.ArrayList<>();
            String summary = "";

            // 종합 요약 추출
            int summaryStart = markdown.indexOf("### 종합 요약\n");
            if (summaryStart >= 0) {
                int summaryEnd = markdown.indexOf("\n\n", summaryStart + 14);
                if (summaryEnd > summaryStart) {
                    summary = markdown.substring(summaryStart + 14, summaryEnd).trim();
                }
            }

            // 요구사항 상세 섹션 파싱
            String detailMarker = "### 요구사항 상세";
            int detailStart = markdown.indexOf(detailMarker);
            if (detailStart < 0) return null;

            String detailSection = markdown.substring(detailStart);
            String[] blocks = detailSection.split("####\\s+");

            for (String block : blocks) {
                if (block.isBlank() || block.startsWith("#")) continue;

                String id = "", item = "", importance = "", category = "", description = "";
                String matchStatus = "", proposalContent = "", gapDescription = "";

                // "REQ-01. 항목명 [상]"
                int dotIdx = block.indexOf('.');
                int bracketOpen = block.indexOf('[');
                int bracketClose = block.indexOf(']');
                if (dotIdx > 0 && bracketOpen > dotIdx && bracketClose > bracketOpen) {
                    id = block.substring(0, dotIdx).trim();
                    item = block.substring(dotIdx + 1, bracketOpen).trim();
                    importance = block.substring(bracketOpen + 1, bracketClose).trim();
                }

                for (String line : block.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("- **분류**:")) category = line.substring(11).trim();
                    else if (line.startsWith("- **요구사항**:")) description = line.substring(14).trim();
                    else if (line.startsWith("- **대응상태**:")) matchStatus = line.substring(14).trim();
                    else if (line.startsWith("- **제안서 대응 내용**:")) proposalContent = line.substring(21).trim();
                    else if (line.startsWith("- **갭/미흡 사항**:")) gapDescription = line.substring(18).trim();
                }

                if (!id.isEmpty()) {
                    entries.add(new GapEntry(
                            new Requirement(id, category, item, description, importance),
                            matchStatus, proposalContent, gapDescription));
                }
            }

            return entries.isEmpty() ? null : new GapMatrix(entries, summary);
        } catch (Exception e) {
            return null;
        }
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 갭 매트릭스\n\n");

        if (summary != null && !summary.isBlank()) {
            sb.append("### 종합 요약\n").append(summary).append("\n\n");
        }

        sb.append("| # | 분류 | 평가항목 | 중요도 | 대응상태 | 갭 설명 |\n");
        sb.append("|---|------|----------|--------|----------|--------|\n");

        for (GapEntry entry : entries) {
            Requirement req = entry.requirement();
            sb.append("| ").append(req.id())
                    .append(" | ").append(req.category())
                    .append(" | ").append(req.item())
                    .append(" | ").append(req.importance())
                    .append(" | ").append(entry.matchStatus())
                    .append(" | ").append(entry.gapDescription() != null ? entry.gapDescription() : "-")
                    .append(" |\n");
        }

        sb.append("\n### 요구사항 상세\n\n");
        for (GapEntry entry : entries) {
            Requirement req = entry.requirement();
            sb.append("#### ").append(req.id()).append(". ").append(req.item()).append(" [").append(req.importance()).append("]\n");
            sb.append("- **분류**: ").append(req.category()).append("\n");
            sb.append("- **요구사항**: ").append(req.description()).append("\n");
            sb.append("- **대응상태**: ").append(entry.matchStatus()).append("\n");
            if (entry.proposalContent() != null && !entry.proposalContent().isBlank()) {
                sb.append("- **제안서 대응 내용**: ").append(entry.proposalContent()).append("\n");
            }
            if (entry.gapDescription() != null && !entry.gapDescription().isBlank()) {
                sb.append("- **갭/미흡 사항**: ").append(entry.gapDescription()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    public List<GapEntry> gapEntries() {
        return entries.stream()
                .filter(e -> !"충족".equals(e.matchStatus()))
                .toList();
    }

    public long countByStatus(String status) {
        return entries.stream().filter(e -> status.equals(e.matchStatus())).count();
    }

    /**
     * 페르소나 관심 분야와 관련된 항목을 상위에 배치하고, 나머지를 뒤에 붙인 마크다운을 생성한다.
     * 관심 분야 키워드가 카테고리 또는 항목명/설명에 포함되면 "관련 항목"으로 판정.
     */
    public String toMarkdownForPersona(String focusAreas) {
        if (focusAreas == null || focusAreas.isBlank()) {
            return toMarkdown();
        }

        List<String> keywords = java.util.Arrays.stream(focusAreas.split("[,、\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        List<GapEntry> relevant = new java.util.ArrayList<>();
        List<GapEntry> others = new java.util.ArrayList<>();

        for (GapEntry entry : entries) {
            if (isRelevant(entry, keywords)) {
                relevant.add(entry);
            } else {
                others.add(entry);
            }
        }

        // 관련 항목을 먼저, 나머지를 뒤에
        List<GapEntry> sorted = new java.util.ArrayList<>(relevant);
        sorted.addAll(others);

        return new GapMatrix(sorted, summary
                + " (이 페르소나 관심 분야 관련 항목 " + relevant.size() + "개 우선 배치)")
                .toMarkdown();
    }

    private boolean isRelevant(GapEntry entry, List<String> keywords) {
        String text = entry.requirement().category() + " "
                + entry.requirement().item() + " "
                + entry.requirement().description();
        return keywords.stream().anyMatch(kw -> text.contains(kw));
    }
}
