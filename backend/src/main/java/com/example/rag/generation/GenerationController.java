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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GenerationResponse create(@RequestBody GenerationRequest request, Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return generationService.startGeneration(request, userId);
    }

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
