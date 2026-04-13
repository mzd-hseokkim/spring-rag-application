package kr.co.mz.ragservice.generation;

import kr.co.mz.ragservice.generation.dto.GenerationRequest;
import kr.co.mz.ragservice.generation.dto.GenerationResponse;
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

    /**
     * 위자드 Step 3: 미배치 요구사항을 위한 새 목차 섹션 자동 생성
     */
    @PostMapping("/{id}/generate-unmapped-sections")
    public GenerationResponse generateUnmappedSections(@PathVariable UUID id) {
        return generationService.generateSectionsForUnmapped(id);
    }

    /**
     * 위자드 Step 4: 섹션 생성 시작
     */
    @PostMapping("/{id}/generate-sections")
    public void generateSections(@PathVariable UUID id, @RequestBody GenerateSectionsRequest request) {
        List<UUID> refDocIds = request.referenceDocumentIds() != null ? request.referenceDocumentIds() : List.of();
        List<String> sectionKeys = request.sectionKeys() != null ? request.sectionKeys() : List.of();
        generationService.startSectionGeneration(id, refDocIds, request.includeWebSearch(), sectionKeys, request.forceRegenerate());
    }

    /**
     * 위자드 Step 4: 개별 섹션 수정 저장
     */
    @PutMapping("/{id}/sections/{key}")
    public GenerationResponse saveSection(@PathVariable UUID id, @PathVariable String key, @RequestBody String sectionJson) {
        return generationService.saveSection(id, key, sectionJson);
    }

    /**
     * 위자드 Step 5: HTML 렌더링 시작
     */
    @PostMapping("/{id}/render")
    public void render(@PathVariable UUID id) {
        generationService.startRendering(id);
    }

    /**
     * 위자드 Step 4: 단일 섹션 재생성
     */
    @PostMapping("/{id}/regenerate-section/{key}")
    public void regenerateSection(@PathVariable UUID id, @PathVariable String key,
                                   @RequestBody GenerateSectionsRequest request) {
        List<UUID> refDocIds = request.referenceDocumentIds() != null ? request.referenceDocumentIds() : List.of();
        String instruction = request.userInstruction() != null ? request.userInstruction() : "";
        generationService.startSingleSectionRegeneration(id, key, refDocIds, request.includeWebSearch(), instruction);
    }

    /**
     * 위자드 Step 4: 기존 섹션 전체 초기화
     */
    @DeleteMapping("/{id}/sections")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearSections(@PathVariable UUID id) {
        generationService.clearSections(id);
    }

    record AnalyzeRequest(java.util.List<UUID> customerDocumentIds) {}
    record GenerateSectionsRequest(java.util.List<UUID> referenceDocumentIds, boolean includeWebSearch, java.util.List<String> sectionKeys, boolean forceRegenerate, String userInstruction) {}

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
        SseEmitter emitter = new SseEmitter(0L); // 타임아웃 없음
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

    @GetMapping(value = "/{id}/download-markdown", produces = "text/markdown; charset=UTF-8")
    public ResponseEntity<String> downloadMarkdown(@PathVariable UUID id) {
        var result = generationService.getMarkdownWithTitle(id);
        String filename = result.title().replaceAll("[\\\\/:*?\"<>|]", "_") + ".md";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"))
                .header(HttpHeaders.CONTENT_TYPE, "text/markdown; charset=UTF-8")
                .body(result.markdown());
    }

    @GetMapping(value = "/{id}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public String preview(@PathVariable UUID id) {
        return generationService.getPreviewHtml(id);
    }

    @PatchMapping("/{id}/title")
    public GenerationResponse updateTitle(@PathVariable UUID id, @RequestBody java.util.Map<String, String> body) {
        return generationService.updateTitle(id, body.get("title"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        generationService.deleteJob(id);
    }
}
