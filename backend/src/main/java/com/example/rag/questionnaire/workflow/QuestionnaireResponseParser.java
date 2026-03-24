package com.example.rag.questionnaire.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QuestionnaireResponseParser {

    private static final Logger log = LoggerFactory.getLogger(QuestionnaireResponseParser.class);
    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:json[n]?)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public QuestionnaireResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<QuestionAnswer> parseQuestions(String raw) {
        String json = extractJson(raw);
        JsonNode node = parseToNode(json);

        List<QuestionAnswer> questions = new ArrayList<>();
        // 배열 형태: [{question, answer, difficulty, category, sources}]
        if (node.isArray()) {
            for (JsonNode item : node) {
                questions.add(parseQuestion(item));
            }
        }
        // 객체 형태: {questions: [...]}
        else if (node.has("questions") && node.get("questions").isArray()) {
            for (JsonNode item : node.get("questions")) {
                questions.add(parseQuestion(item));
            }
        }
        return questions;
    }

    private QuestionAnswer parseQuestion(JsonNode node) {
        return new QuestionAnswer(
                textOrDefault(node, "question", ""),
                textOrDefault(node, "answer", ""),
                textOrDefault(node, "difficulty", "중"),
                textOrDefault(node, "category", "일반"),
                toStringList(node.get("sources"))
        );
    }

    private JsonNode parseToNode(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("Direct parse failed, attempting repair");
        }
        String repaired = repairTruncatedJson(json);
        try {
            return objectMapper.readTree(repaired);
        } catch (Exception e) {
            log.error("JSON parse failed even after repair. Extracted JSON:\n{}",
                    json.substring(0, Math.min(json.length(), 500)));
            throw new RuntimeException("Failed to parse AI response as JSON", e);
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "[]";
        String trimmed = raw.trim();
        Matcher matcher = CODE_BLOCK.matcher(trimmed);
        if (matcher.find()) {
            trimmed = matcher.group(1).trim();
        }
        // JSON 배열 또는 객체 시작점 찾기
        int arrayStart = trimmed.indexOf('[');
        int braceStart = trimmed.indexOf('{');
        if (arrayStart >= 0 && (braceStart < 0 || arrayStart < braceStart)) {
            trimmed = trimmed.substring(arrayStart);
        } else if (braceStart >= 0) {
            trimmed = trimmed.substring(braceStart);
        }
        return trimmed;
    }

    private String repairTruncatedJson(String json) {
        int braces = 0, brackets = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') braces++;
            else if (c == '}') braces--;
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
        }

        if (braces == 0 && brackets == 0 && !inString) return json;

        StringBuilder sb = new StringBuilder(json);
        if (inString) sb.append('"');
        for (int i = 0; i < brackets; i++) sb.append(']');
        for (int i = 0; i < braces; i++) sb.append('}');
        return sb.toString();
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
