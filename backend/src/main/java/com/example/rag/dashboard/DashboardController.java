package com.example.rag.dashboard;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    public List<Map<String, Object>> tokenTrend(@RequestParam(defaultValue = "30") int days) {
        return dashboardService.getTokenTrend(days);
    }

    @GetMapping("/token-by-user")
    public List<Map<String, Object>> tokenByUser(@RequestParam(defaultValue = "30") int days) {
        return dashboardService.getTokenByUser(days);
    }

    @GetMapping("/token-by-model")
    public List<Map<String, Object>> tokenByModel(@RequestParam(defaultValue = "30") int days) {
        return dashboardService.getTokenByModel(days);
    }

    @GetMapping("/traces")
    public Page<PipelineTraceEntity> traces(@PageableDefault(size = 20) Pageable pageable) {
        return dashboardService.getTraces(pageable);
    }
}
