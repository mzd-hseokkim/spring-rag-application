package kr.co.mz.ragservice.observability;

import kr.co.mz.ragservice.dashboard.PipelineTraceEntity;
import kr.co.mz.ragservice.dashboard.PipelineTraceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class PipelineTracer {

    private static final Logger log = LoggerFactory.getLogger("pipeline.trace");

    private final ObjectMapper objectMapper;
    private final PipelineTraceRepository traceRepository;

    public PipelineTracer(ObjectMapper objectMapper, PipelineTraceRepository traceRepository) {
        this.objectMapper = objectMapper;
        this.traceRepository = traceRepository;
    }

    public void logTrace(TraceContext context) {
        logTrace(context, null, null);
    }

    public void logTrace(TraceContext context, UUID userId, String agentAction) {
        try {
            Map<String, Object> map = context.toMap();
            String json = objectMapper.writeValueAsString(map);
            log.info("{}", json);

            // DB 저장
            String stepsJson = objectMapper.writeValueAsString(map.get("steps"));
            int totalLatency = ((Number) map.get("totalLatencyMs")).intValue();

            var entity = new PipelineTraceEntity(
                    context.getTraceId(),
                    (String) map.get("sessionId"),
                    userId,
                    (String) map.get("query"),
                    agentAction,
                    totalLatency,
                    stepsJson
            );
            traceRepository.save(entity);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize trace: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to save trace to DB: {}", e.getMessage());
        }
    }
}
