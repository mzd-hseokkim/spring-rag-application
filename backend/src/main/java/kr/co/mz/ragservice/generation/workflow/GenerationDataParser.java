package kr.co.mz.ragservice.generation.workflow;

import kr.co.mz.ragservice.common.RagException;
import kr.co.mz.ragservice.questionnaire.workflow.Requirement;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GenerationDataParser {

    private static final Logger log = LoggerFactory.getLogger(GenerationDataParser.class);
    private static final String FIELD_REQUIREMENTS = "requirements";
    private static final String FIELD_MAPPING = "mapping";
    private static final String FIELD_RFP_MANDATES = "rfpMandates";

    private final ObjectMapper objectMapper;

    public GenerationDataParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Requirement> parseRequirementsFromMapping(String requirementMappingJson) {
        if (requirementMappingJson == null) return List.of();
        try {
            var parsed = objectMapper.readTree(requirementMappingJson);
            if (parsed.has(FIELD_REQUIREMENTS) && parsed.get(FIELD_REQUIREMENTS).size() > 0) {
                return objectMapper.readValue(
                        parsed.get(FIELD_REQUIREMENTS).toString(),
                        new TypeReference<List<Requirement>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to parse existing requirements: {}", e.getMessage());
        }
        return List.of();
    }

    public Map<String, List<String>> parseMappingFromJson(String requirementMappingJson) {
        if (requirementMappingJson == null) return Map.of();
        try {
            var parsed = objectMapper.readTree(requirementMappingJson);
            return objectMapper.readValue(
                    parsed.get(FIELD_MAPPING).toString(),
                    new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse requirement mapping: {}", e.getMessage());
        }
        return Map.of();
    }

    /**
     * requirementMapping JSON에서 RFP 의무 항목/배점 묶음을 추출.
     * 필드가 없으면 빈 RfpMandates를 반환 (신규 필드, 기존 데이터 호환).
     */
    public RfpMandates parseRfpMandatesFromMapping(String requirementMappingJson) {
        if (requirementMappingJson == null) return RfpMandates.empty();
        try {
            var parsed = objectMapper.readTree(requirementMappingJson);
            if (parsed.has(FIELD_RFP_MANDATES) && !parsed.get(FIELD_RFP_MANDATES).isNull()) {
                return objectMapper.readValue(
                        parsed.get(FIELD_RFP_MANDATES).toString(),
                        RfpMandates.class);
            }
        } catch (Exception e) {
            log.warn("Failed to parse RFP mandates: {}", e.getMessage());
        }
        return RfpMandates.empty();
    }

    public List<SectionContent> parseSections(String sectionsJson) {
        if (sectionsJson == null) return List.of();
        try {
            return objectMapper.readValue(sectionsJson,
                    new TypeReference<List<SectionContent>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse sections JSON: {}", e.getMessage());
        }
        return List.of();
    }

    public String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RagException("Failed to serialize to JSON", e);
        }
    }
}
