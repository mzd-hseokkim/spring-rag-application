package com.example.rag.dashboard;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return dashboardService.getOverview();
    }

    @GetMapping("/chat-trend")
    public List<Map<String, Object>> chatTrend(@RequestParam(defaultValue = "30") int days) {
        return dashboardService.getChatTrend(days);
    }

    @GetMapping("/agent-distribution")
    public List<Map<String, Object>> agentDistribution() {
        return dashboardService.getAgentDistribution();
    }

    @GetMapping("/token-trend")
    public List<Map<String, Object>> tokenTrend(@RequestParam(defaultValue = "30") int days,
                                                 @RequestParam(required = false) String purpose) {
        return dashboardService.getTokenTrend(days, purpose);
    }

    @GetMapping("/token-by-user")
    public List<Map<String, Object>> tokenByUser(@RequestParam(defaultValue = "30") int days,
                                                  @RequestParam(required = false) String purpose) {
        return dashboardService.getTokenByUser(days, purpose);
    }

    @GetMapping("/token-by-model")
    public List<Map<String, Object>> tokenByModel(@RequestParam(defaultValue = "30") int days,
                                                   @RequestParam(required = false) String purpose) {
        return dashboardService.getTokenByModel(days, purpose);
    }

    @GetMapping("/token-by-purpose")
    public List<Map<String, Object>> tokenByPurpose(@RequestParam(defaultValue = "30") int days) {
        return dashboardService.getTokenByPurpose(days);
    }

    @GetMapping("/token-cost")
    public List<Map<String, Object>> tokenCost(@RequestParam(defaultValue = "30") int days) {
        return dashboardService.getTokenCost(days);
    }

    @GetMapping("/token-cost-by-user")
    public List<Map<String, Object>> tokenCostByUser(@RequestParam(defaultValue = "30") int days) {
        return dashboardService.getTokenCostByUser(days);
    }

    @GetMapping("/generation-trend")
    public List<Map<String, Object>> generationTrend(@RequestParam(defaultValue = "30") int days) {
        return dashboardService.getGenerationTrend(days);
    }

    @GetMapping("/traces")
    public Page<PipelineTraceEntity> traces(@PageableDefault(size = 20) Pageable pageable) {
        return dashboardService.getTraces(pageable);
    }

    @GetMapping("/generation-traces")
    public Page<GenerationTraceEntity> generationTraces(@PageableDefault(size = 20) Pageable pageable) {
        return dashboardService.getGenerationTraces(pageable);
    }

    @GetMapping("/generation-traces/{jobId}")
    public List<GenerationTraceEntity> generationTracesByJob(@PathVariable UUID jobId) {
        return dashboardService.getGenerationTracesByJob(jobId);
    }
}
