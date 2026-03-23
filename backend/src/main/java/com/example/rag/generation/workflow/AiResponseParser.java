package com.example.rag.generation.workflow;

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
    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:json[n]?)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public AiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DocumentOutline parseOutline(String raw) {
        JsonNode node = parseToNode(raw);
        String title = textOrDefault(node, "title", "Untitled");
        String summary = textOrDefault(node, "summary", "");
        List<SectionPlan> sections = new ArrayList<>();
        if (node.has("sections") && node.get("sections").isArray()) {
            for (JsonNode s : node.get("sections")) {
                sections.add(new SectionPlan(
                        textOrDefault(s, "key", "section_" + sections.size()),
                        textOrDefault(s, "heading", "섹션 " + (sections.size() + 1)),
                        textOrDefault(s, "purpose", ""),
                        toStringList(s.get("keyPoints")),
                        s.has("estimatedLength") ? s.get("estimatedLength").asInt(500) : 500
                ));
            }
        }
        return new DocumentOutline(title, summary, sections);
    }

    public SectionContent parseSection(String raw) {
        JsonNode node = parseToNode(raw);
        String key = textOrDefault(node, "key", "unknown");
        String title = textOrDefault(node, "title", "");
        String content = textOrDefault(node, "content", "");
        List<String> highlights = toStringList(node.get("highlights"));
        List<String> references = toStringList(node.get("references"));
        List<ContentTable> tables = parseTables(node.get("tables"));
        return new SectionContent(key, title, content, highlights, tables, references);
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
            throw new RuntimeException("Failed to parse AI response as JSON", e);
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
        // 열린 괄호/따옴표를 추적해서 닫아주기
        int braces = 0, brackets = 0;
        boolean inString = false;
        boolean escaped = false;
        int lastValidPos = 0;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') braces++;
            else if (c == '}') { braces--; if (braces == 0) lastValidPos = i + 1; }
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
        }

        // 이미 유효하면 그대로 반환
        if (braces == 0 && brackets == 0 && !inString) return json;

        // 마지막으로 완전한 객체 위치까지 자르거나, 괄호를 닫아주기
        StringBuilder sb = new StringBuilder(json);
        if (inString) sb.append('"');
        // 열린 brackets/braces 닫기
        for (int i = 0; i < brackets; i++) sb.append(']');
        for (int i = 0; i < braces; i++) sb.append('}');

        return sb.toString();
    }

    private List<ContentTable> parseTables(JsonNode tablesNode) {
        List<ContentTable> tables = new ArrayList<>();
        if (tablesNode == null || !tablesNode.isArray()) return tables;
        for (JsonNode t : tablesNode) {
            // caption 또는 title
            String caption = t.has("caption") ? t.get("caption").asText("")
                    : t.has("title") ? t.get("title").asText("") : "";
            List<String> headers = toStringList(t.get("headers"));
            List<List<String>> rows = parseRows(t.get("rows"));
            tables.add(new ContentTable(caption, headers, rows));
        }
        return tables;
    }

    private List<List<String>> parseRows(JsonNode rowsNode) {
        List<List<String>> rows = new ArrayList<>();
        if (rowsNode == null || !rowsNode.isArray()) return rows;
        for (JsonNode row : rowsNode) {
            if (row.isArray()) {
                // 정상: [["a","b"],["c","d"]]
                rows.add(toStringList(row));
            } else if (row.isObject()) {
                // 비정상: [{"cells":["a","b"]},...]
                if (row.has("cells") && row.get("cells").isArray()) {
                    rows.add(toStringList(row.get("cells")));
                } else {
                    // 다른 형식의 object — 값들을 그냥 모음
                    List<String> values = new ArrayList<>();
                    row.fields().forEachRemaining(e -> values.add(e.getValue().asText("")));
                    rows.add(values);
                }
            }
        }
        return rows;
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
}
