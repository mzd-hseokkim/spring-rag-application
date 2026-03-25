package com.example.rag.generation.workflow;

import com.example.rag.common.RagException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 응답의 JSON을 안정적으로 파싱한다.
 * - 마크다운 코드블록 제거
 * - 잘린 JSON 복구 시도
 * - 스키마 불일치(rows 형식 등) 자동 보정
 */
@Component
public class AiResponseParser {

    private static final Logger log = LoggerFactory.getLogger(AiResponseParser.class);
    private static final String TITLE_FIELD = "title";
    private static final String SECTIONS_FIELD = "sections";
    private static final String CELLS_FIELD = "cells";
    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public AiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DocumentOutline parseOutline(String raw) {
        JsonNode node = parseToNode(raw);
        String title = textOrDefault(node, TITLE_FIELD, "Untitled");
        String summary = textOrDefault(node, "summary", "");
        List<SectionPlan> sections = parseSectionPlans(node);
        return new DocumentOutline(title, summary, sections);
    }

    public SectionContent parseSection(String raw) {
        JsonNode node = parseToNode(raw);
        String key = textOrDefault(node, "key", "unknown");
        String title = textOrDefault(node, TITLE_FIELD, "");
        String content = textOrDefault(node, "content", "");
        List<String> highlights = toStringList(node != null ? node.get("highlights") : null);
        List<String> references = toStringList(node != null ? node.get("references") : null);
        List<ContentTable> tables = parseTables(node != null ? node.get("tables") : null);
        String layoutType = textOrDefault(node, "layoutType", "TEXT_FULL");
        java.util.Map<String, Object> layoutData = parseLayoutData(node != null ? node.get("layoutData") : null);
        String governingMessage = textOrDefault(node, "governingMessage", "");
        String visualGuide = textOrDefault(node, "visualGuide", "");
        // sources는 백엔드에서 채우므로 빈 배열로 초기화
        return new SectionContent(key, title, content, highlights, tables, references, layoutType, layoutData, governingMessage, visualGuide, List.of());
    }

    private List<SectionPlan> parseSectionPlans(JsonNode node) {
        List<SectionPlan> sections = new ArrayList<>();
        if (node == null || !node.has(SECTIONS_FIELD)) return sections;
        JsonNode sectionsNode = node.get(SECTIONS_FIELD);
        if (sectionsNode == null || !sectionsNode.isArray()) return sections;
        for (JsonNode s : sectionsNode) {
            if (s == null) continue;
            sections.add(new SectionPlan(
                    textOrDefault(s, "key", "section_" + sections.size()),
                    textOrDefault(s, "heading", "섹션 " + (sections.size() + 1)),
                    textOrDefault(s, "purpose", ""),
                    toStringList(s.get("keyPoints")),
                    s.has("estimatedLength") ? s.get("estimatedLength").asInt(500) : 500
            ));
        }
        return sections;
    }

    private JsonNode parseToNode(String raw) {
        String json = extractJson(raw);
        // 1차 시도: 그대로 파싱
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("Direct parse failed, attempting repair");
        }
        // 2차 시도: 잘린 JSON 복구
        String repaired = repairTruncatedJson(json);
        try {
            return objectMapper.readTree(repaired);
        } catch (Exception e) {
            log.error("JSON parse failed even after repair. Extracted JSON:\n{}", json.substring(0, Math.min(json.length(), 500)));
            throw new RagException("Failed to parse AI response as JSON", e);
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        String trimmed = raw.trim();
        // 마크다운 코드블록 제거
        Matcher matcher = CODE_BLOCK.matcher(trimmed);
        if (matcher.find()) {
            trimmed = matcher.group(1).trim();
        }
        // JSON 시작점 찾기 (앞에 텍스트가 있을 수 있음)
        int braceStart = trimmed.indexOf('{');
        if (braceStart > 0) {
            trimmed = trimmed.substring(braceStart);
        }
        return trimmed;
    }

    private String repairTruncatedJson(String json) {
        int[] counts = countUnclosedBrackets(json);
        int braces = counts[0];
        int brackets = counts[1];
        boolean inString = counts[2] != 0;

        if (braces == 0 && brackets == 0 && !inString) return json;

        StringBuilder sb = new StringBuilder(json);
        if (inString) sb.append('"');
        for (int i = 0; i < brackets; i++) sb.append(']');
        for (int i = 0; i < braces; i++) sb.append('}');
        return sb.toString();
    }

    /** Returns [braces, brackets, inString(0 or 1)] */
    private int[] countUnclosedBrackets(String json) {
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                switch (c) {
                    case '{' -> braces++;
                    case '}' -> braces--;
                    case '[' -> brackets++;
                    case ']' -> brackets--;
                    default -> { /* ignore */ }
                }
            }
        }
        return new int[]{braces, brackets, inString ? 1 : 0};
    }

    private List<ContentTable> parseTables(JsonNode tablesNode) {
        List<ContentTable> tables = new ArrayList<>();
        if (tablesNode == null || !tablesNode.isArray()) return tables;
        for (JsonNode t : tablesNode) {
            String caption = extractCaption(t);
            List<String> headers = toStringList(t.get("headers"));
            List<List<String>> rows = parseRows(t.get("rows"));
            tables.add(new ContentTable(caption, headers, rows));
        }
        return tables;
    }

    private String extractCaption(JsonNode node) {
        if (node.has("caption")) return node.get("caption").asText("");
        if (node.has(TITLE_FIELD)) return node.get(TITLE_FIELD).asText("");
        return "";
    }

    private List<List<String>> parseRows(JsonNode rowsNode) {
        List<List<String>> rows = new ArrayList<>();
        if (rowsNode == null || !rowsNode.isArray()) return rows;
        for (JsonNode row : rowsNode) {
            if (row.isArray()) {
                rows.add(toStringList(row));
            } else if (row.isObject()) {
                rows.add(parseObjectRow(row));
            }
        }
        return rows;
    }

    private List<String> parseObjectRow(JsonNode row) {
        if (row.has(CELLS_FIELD) && row.get(CELLS_FIELD).isArray()) {
            return toStringList(row.get(CELLS_FIELD));
        }
        List<String> values = new ArrayList<>();
        row.fields().forEachRemaining(e -> values.add(e.getValue().asText("")));
        return values;
    }

    private List<String> toStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node == null || !node.isArray()) return list;
        for (JsonNode item : node) {
            list.add(item.asText(""));
        }
        return list;
    }

    private String textOrDefault(JsonNode node, String field, String defaultValue) {
        if (node == null || !node.has(field)) return defaultValue;
        return node.get(field).asText(defaultValue);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> parseLayoutData(JsonNode node) {
        if (node == null || !node.isObject()) return java.util.Map.of();
        try {
            return objectMapper.convertValue(node, java.util.Map.class);
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }
}
