package com.example.rag.generation.renderer;

import com.example.rag.generation.dto.OutlineNode;
import com.example.rag.generation.workflow.ContentTable;
import com.example.rag.generation.workflow.DocumentOutline;
import com.example.rag.generation.workflow.SectionContent;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class MarkdownRenderer {

    public String render(DocumentOutline outline, List<SectionContent> sections) {
        var sb = new StringBuilder();
        List<OutlineNode> outlineNodes = outline.outlineNodes() != null
                ? outline.outlineNodes() : List.of();

        // 문서 제목
        sb.append("# ").append(outline.title()).append("\n\n");
        if (outline.summary() != null && !outline.summary().isBlank()) {
            sb.append(outline.summary()).append("\n\n");
        }

        // 전체 목차 (outline 트리 순서)
        sb.append("---\n\n## 목차\n\n");
        renderToc(sb, outlineNodes, 0);
        sb.append("\n---\n\n");

        // key → 조상 breadcrumb 맵
        Map<String, List<String>> ancestorMap = new LinkedHashMap<>();
        buildAncestorMap(outlineNodes, List.of(), ancestorMap);

        // section key → content 맵
        Map<String, SectionContent> sectionMap = new LinkedHashMap<>();
        for (SectionContent s : sections) sectionMap.put(s.key(), s);

        // outline 트리 순서대로 렌더링 (내용 없는 항목도 목차 제목으로 표시)
        renderOutlineTree(sb, outlineNodes, sectionMap, ancestorMap, 1);

        return sb.toString();
    }

    /** 목차를 outline 트리 구조대로 렌더링 */
    private void renderToc(StringBuilder sb, List<OutlineNode> nodes, int depth) {
        for (OutlineNode node : nodes) {
            String indent = "  ".repeat(depth);
            sb.append(indent).append("- **").append(node.key()).append(".** ").append(node.title()).append("\n");
            if (!node.children().isEmpty()) {
                renderToc(sb, node.children(), depth + 1);
            }
        }
    }

    /** outline 트리를 DFS로 순회하며 본문 렌더링 */
    private void renderOutlineTree(StringBuilder sb, List<OutlineNode> nodes,
                                    Map<String, SectionContent> sectionMap,
                                    Map<String, List<String>> ancestorMap, int headingLevel) {
        for (OutlineNode node : nodes) {
            boolean isLeaf = node.children().isEmpty();
            String heading = "#".repeat(Math.min(headingLevel + 1, 6));

            if (isLeaf) {
                SectionContent section = sectionMap.get(node.key());
                sb.append("---\n\n");

                // breadcrumb
                List<String> ancestors = ancestorMap.getOrDefault(node.key(), List.of());
                if (!ancestors.isEmpty()) {
                    sb.append("> ").append(String.join(" > ", ancestors)).append("\n\n");
                }

                sb.append(heading).append(" ").append(node.key()).append(". ").append(node.title()).append("\n\n");

                if (section != null) {
                    renderSectionContent(sb, section);
                } else {
                    sb.append("*（내용 미생성）*\n\n");
                }
            } else {
                // 비-leaf: 챕터/섹션 제목 표시
                sb.append("\n---\n\n");
                sb.append(heading).append(" ").append(node.key()).append(". ").append(node.title()).append("\n\n");

                // 1depth 노드: 하위 항목 목록 표시
                if (headingLevel == 1) {
                    for (OutlineNode child : node.children()) {
                        sb.append("- **").append(child.key()).append(".** ").append(child.title()).append("\n");
                    }
                    sb.append("\n");
                }

                renderOutlineTree(sb, node.children(), sectionMap, ancestorMap, headingLevel + 1);
            }
        }
    }

    private void renderSectionContent(StringBuilder sb, SectionContent section) {
        if (section.content() != null && !section.content().isBlank()) {
            sb.append(section.content()).append("\n\n");
        }
        if (section.highlights() != null && !section.highlights().isEmpty()) {
            sb.append("**주요 포인트:**\n\n");
            for (String highlight : section.highlights()) {
                sb.append("- ").append(highlight).append("\n");
            }
            sb.append("\n");
        }
        if (section.tables() != null && !section.tables().isEmpty()) {
            for (ContentTable table : section.tables()) {
                renderTable(sb, table);
            }
        }
        if (section.references() != null && !section.references().isEmpty()) {
            sb.append("**참조:**\n\n");
            for (String ref : section.references()) {
                sb.append("- ").append(ref).append("\n");
            }
            sb.append("\n");
        }
    }

    private void buildAncestorMap(List<OutlineNode> nodes, List<String> ancestors,
                                   Map<String, List<String>> result) {
        for (OutlineNode node : nodes) {
            result.put(node.key(), ancestors);
            if (!node.children().isEmpty()) {
                List<String> childAncestors = new ArrayList<>(ancestors);
                childAncestors.add("**" + node.key() + ".** " + node.title());
                buildAncestorMap(node.children(), childAncestors, result);
            }
        }
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
