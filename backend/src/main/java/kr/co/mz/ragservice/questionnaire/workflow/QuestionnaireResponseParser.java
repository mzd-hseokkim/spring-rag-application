package kr.co.mz.ragservice.questionnaire.workflow;

import kr.co.mz.ragservice.common.RagException;
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
    private static final String QUESTIONS_FIELD = "questions";
    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

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
        // Object wrapping a questions array
        else if (node.has(QUESTIONS_FIELD) && node.get(QUESTIONS_FIELD).isArray()) {
            for (JsonNode item : node.get(QUESTIONS_FIELD)) {
                questions.add(parseQuestion(item));
            }
        }
        return questions;
    }

    private QuestionAnswer parseQuestion(JsonNode node) {
        JsonNode sourcesNode = node != null ? node.get("sources") : null;
        return new QuestionAnswer(
                textOrDefault(node, "question", ""),
                textOrDefault(node, "answer", ""),
                textOrDefault(node, "difficulty", "중"),
                textOrDefault(node, "category", "일반"),
                toStringList(sourcesNode)
        );
    }

    private JsonNode parseToNode(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("Direct parse failed, attempting repair: {}", e.getMessage());
        }
        // 1차: 괄호 보충
        String repaired = repairTruncatedJson(json);
        try {
            return objectMapper.readTree(repaired);
        } catch (Exception e) {
            log.debug("Bracket repair failed, attempting truncation: {}", e.getMessage());
        }
        // 2차: 마지막 불완전 항목 제거 후 배열 닫기
        String truncated = truncateToLastComplete(json);
        if (truncated != null) {
            try {
                return objectMapper.readTree(truncated);
            } catch (Exception e) {
                log.debug("Truncation repair also failed: {}", e.getMessage());
            }
        }
        if (log.isErrorEnabled()) {
            log.error("JSON parse failed after all repair attempts. Extracted JSON (first 500 chars):\n{}",
                    json.substring(0, Math.min(json.length(), 500)));
        }
        throw new RagException("Failed to parse AI response as JSON");
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

    /**
     * 마지막으로 완성된 JSON 객체(}로 끝나는)까지만 남기고 배열을 닫는다.
     * LLM이 중간에 잘못된 JSON을 생성했을 때, 정상 파싱된 항목만 살린다.
     */
    private String truncateToLastComplete(String json) {
        // 마지막 완전한 "}" 를 찾아서 그 뒤를 잘라내고 "]" 로 닫기
        int lastCloseBrace = json.lastIndexOf('}');
        if (lastCloseBrace <= 0) return null;
        String candidate = json.substring(0, lastCloseBrace + 1);
        // 배열 시작 "[" 이 있어야 함
        int arrayStart = candidate.indexOf('[');
        if (arrayStart < 0) return null;
        // 마지막 "}" 뒤에 쉼표가 있을 수 있으므로 제거하고 "]" 닫기
        String trimmed = candidate.substring(arrayStart).stripTrailing();
        if (trimmed.endsWith(",")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed + "]";
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
