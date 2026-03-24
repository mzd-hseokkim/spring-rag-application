package com.example.rag.questionnaire;

import com.example.rag.questionnaire.dto.QuestionnaireRequest;
import com.example.rag.questionnaire.dto.QuestionnaireResponse;
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
@RequestMapping("/api/questionnaires")
public class QuestionnaireController {

    private final QuestionnaireService questionnaireService;
    private final QuestionnaireEmitterManager emitterManager;

    public QuestionnaireController(QuestionnaireService questionnaireService,
                                   QuestionnaireEmitterManager emitterManager) {
        this.questionnaireService = questionnaireService;
        this.emitterManager = emitterManager;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionnaireResponse create(@RequestBody QuestionnaireRequest request, Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return questionnaireService.startGeneration(request, userId);
    }

    @GetMapping("/{id}")
    public QuestionnaireResponse get(@PathVariable UUID id) {
        return questionnaireService.getJob(id);
    }

    @GetMapping
    public List<QuestionnaireResponse> list(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return questionnaireService.getJobsByUser(userId);
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id) {
        SseEmitter emitter = new SseEmitter(600_000L);
        emitterManager.register(id, emitter);
        return emitter;
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        Resource resource = questionnaireService.getOutputFile(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }

    @GetMapping(value = "/{id}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public String preview(@PathVariable UUID id) {
        return questionnaireService.getPreviewHtml(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        questionnaireService.deleteJob(id);
    }
}
