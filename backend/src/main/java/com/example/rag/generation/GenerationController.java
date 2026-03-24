package com.example.rag.generation;

import com.example.rag.generation.dto.GenerationRequest;
import com.example.rag.generation.dto.GenerationResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/generations")
public class GenerationController {

    private final GenerationService generationService;
    private final GenerationEmitterManager emitterManager;

    public GenerationController(GenerationService generationService,
                                GenerationEmitterManager emitterManager) {
        this.generationService = generationService;
        this.emitterManager = emitterManager;
    }

    /**
     * 위자드 Step 1: 작업 생성 (DRAFT)
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GenerationResponse create(@RequestBody GenerationRequest request, Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return generationService.createWizardJob(request, userId);
    }

    /**
     * 위자드 Step 2: 목차 추출 시작
     */
    @PostMapping("/{id}/analyze")
    public void analyze(@PathVariable UUID id, @RequestBody AnalyzeRequest request) {
        generationService.startOutlineExtraction(id, request.customerDocumentIds());
    }

    /**
     * 위자드 Step 2: 목차 사용자 수정 저장
     */
    @PutMapping("/{id}/outline")
    public GenerationResponse saveOutline(@PathVariable UUID id, @RequestBody String outlineJson) {
        return generationService.saveOutline(id, outlineJson);
    }

    /**
     * 위자드 Step 3: 요구사항 매핑 시작
     */
    @PostMapping("/{id}/map-requirements")
    public void mapRequirements(@PathVariable UUID id, @RequestBody AnalyzeRequest request) {
        generationService.startRequirementMapping(id, request.customerDocumentIds());
    }

    /**
     * 위자드 Step 3: 요구사항 매핑 수정 저장
     */
    @PutMapping("/{id}/requirements")
    public GenerationResponse saveRequirements(@PathVariable UUID id, @RequestBody String mappingJson) {
        return generationService.saveRequirementMapping(id, mappingJson);
    }

    record AnalyzeRequest(java.util.List<UUID> customerDocumentIds) {}

    @GetMapping("/{id}")
    public GenerationResponse get(@PathVariable UUID id) {
        return generationService.getJob(id);
    }

    @GetMapping
    public List<GenerationResponse> list(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return generationService.getJobsByUser(userId);
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id) {
        SseEmitter emitter = new SseEmitter(600_000L);
        emitterManager.register(id, emitter);
        return emitter;
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        Resource resource = generationService.getOutputFile(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }

    @GetMapping(value = "/{id}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public String preview(@PathVariable UUID id) {
        return generationService.getPreviewHtml(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        generationService.deleteJob(id);
    }
}
