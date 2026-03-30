package com.example.rag.generation.renderer;

import com.example.rag.generation.workflow.ContentTable;
import com.example.rag.generation.workflow.DocumentOutline;
import com.example.rag.generation.workflow.SectionContent;
import com.example.rag.generation.workflow.SectionPlan;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MarkdownRenderer {

    public String render(DocumentOutline outline, List<SectionContent> sections) {
        var sb = new StringBuilder();

        // 문서 제목
        sb.append("# ").append(outline.title()).append("\n\n");
        if (outline.summary() != null && !outline.summary().isBlank()) {
            sb.append(outline.summary()).append("\n\n");
        }

        // 전체 목차
        sb.append("---\n\n## 목차\n\n");
        for (SectionPlan plan : outline.sections()) {
            int depth = plan.key().split("\\.").length;
            String indent = "  ".repeat(depth - 1);
            sb.append(indent).append("- **").append(plan.key()).append(".** ").append(plan.heading()).append("\n");
        }
        sb.append("\n---\n\n");

        // depth별 제목 맵 구성 (breadcrumb용)
        Map<String, String> headingMap = new LinkedHashMap<>();
        for (SectionPlan plan : outline.sections()) {
            headingMap.put(plan.key(), plan.heading());
        }

        // 챕터 간지 구성 (1 depth → 하위 2 depth 목록)
        Map<String, List<Map.Entry<String, String>>> chapterSubItems = new LinkedHashMap<>();
        for (SectionPlan plan : outline.sections()) {
            if (!plan.key().contains(".")) {
                chapterSubItems.put(plan.key(), new java.util.ArrayList<>());
            }
        }
        for (SectionPlan plan : outline.sections()) {
            String[] parts = plan.key().split("\\.");
            if (parts.length == 2) {
                var list = chapterSubItems.get(parts[0]);
                if (list != null) list.add(Map.entry(plan.key(), plan.heading()));
            }
        }

        // 섹션 본문
        String lastChapterKey = null;
        for (SectionContent section : sections) {
            String[] keyParts = section.key().split("\\.");
            int depth = keyParts.length;
            String chapterKey = keyParts[0];

            // 챕터 간지: 새로운 1 depth 챕터 시작 시 삽입
            if (!chapterKey.equals(lastChapterKey)) {
                lastChapterKey = chapterKey;
                String chapterTitle = headingMap.getOrDefault(chapterKey, "");
                sb.append("\n---\n\n");
                sb.append("## ").append(chapterKey).append(". ").append(chapterTitle).append("\n\n");
                var subItems = chapterSubItems.get(chapterKey);
                if (subItems != null && !subItems.isEmpty()) {
                    for (var sub : subItems) {
                        sb.append("- **").append(sub.getKey()).append(".** ").append(sub.getValue()).append("\n");
                    }
                    sb.append("\n");
                }
            }

            // --- 섹션 구분선 ---
            sb.append("---\n\n");

            // breadcrumb: 상위 경로 표시
            String breadcrumb = buildBreadcrumb(section.key(), headingMap);
            if (!breadcrumb.isEmpty()) {
                sb.append("> ").append(breadcrumb).append("\n\n");
            }

            // 장표 번호 + 제목
            String heading = "#".repeat(Math.min(depth + 1, 6));
            sb.append(heading).append(" ").append(section.key()).append(". ").append(section.title()).append("\n\n");

            // 본문 내용
            if (section.content() != null && !section.content().isBlank()) {
                sb.append(section.content()).append("\n\n");
            }

            // 하이라이트
            if (section.highlights() != null && !section.highlights().isEmpty()) {
                sb.append("**주요 포인트:**\n\n");
                for (String highlight : section.highlights()) {
                    sb.append("- ").append(highlight).append("\n");
                }
                sb.append("\n");
            }

            // 테이블
            if (section.tables() != null && !section.tables().isEmpty()) {
                for (ContentTable table : section.tables()) {
                    renderTable(sb, table);
                }
            }

            // 참조
            if (section.references() != null && !section.references().isEmpty()) {
                sb.append("**참조:**\n\n");
                for (String ref : section.references()) {
                    sb.append("- ").append(ref).append("\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 섹션 key에서 상위 경로 breadcrumb 생성.
     * "1.1.1" → "📂 1. 사업 이해 및 추진 전략 > 1.1. 사업 배경 및 필요성"
     */
    private String buildBreadcrumb(String key, Map<String, String> headingMap) {
        String[] parts = key.split("\\.");
        if (parts.length < 2) return "";

        var sb = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            String ancestorKey = String.join(".", java.util.Arrays.copyOfRange(parts, 0, i));
            String title = headingMap.get(ancestorKey);
            if (title == null) continue;
            if (!sb.isEmpty()) sb.append(" > ");
            sb.append("**").append(ancestorKey).append(".** ").append(title);
        }
        return sb.toString();
    }

    private void renderTable(StringBuilder sb, ContentTable table) {
        if (table.caption() != null && !table.caption().isBlank()) {
            sb.append("**").append(table.caption()).append("**\n\n");
        }

        if (table.headers() == null || table.headers().isEmpty()) return;

        sb.append("| ").append(String.join(" | ", table.headers())).append(" |\n");
        sb.append("| ").append(table.headers().stream().map(h -> "---").collect(Collectors.joining(" | "))).append(" |\n");
        if (table.rows() != null) {
            for (List<String> row : table.rows()) {
                sb.append("| ").append(String.join(" | ", row)).append(" |\n");
            }
        }
        sb.append("\n");
    }
}
