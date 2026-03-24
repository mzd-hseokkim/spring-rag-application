package com.example.rag.generation.workflow;

import com.example.rag.common.RagException;
import com.example.rag.generation.GenerationEmitterManager;
import com.example.rag.generation.GenerationJob;
import com.example.rag.generation.GenerationJobRepository;
import com.example.rag.generation.GenerationStatus;
import com.example.rag.generation.dto.GenerationProgressEvent;
import com.example.rag.generation.dto.OutlineNode;
import com.example.rag.generation.renderer.DocumentRendererService;
import com.example.rag.questionnaire.workflow.Requirement;
import com.example.rag.questionnaire.workflow.RequirementExtractor;
import com.example.rag.questionnaire.workflow.QuestionnaireGeneratorService;
import com.example.rag.questionnaire.workflow.TavilySearchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.example.rag.search.ChunkSearchResult;
import com.example.rag.search.SearchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GenerationWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(GenerationWorkflowService.class);
    private static final int MAX_RETRIES = 2;

    private final ContentGeneratorService contentGenerator;
    private final OutlineExtractor outlineExtractor;
    private final RequirementExtractor requirementExtractor;
    private final RequirementMapper requirementMapper;
    private final TavilySearchService tavilySearchService;
    private final DocumentRendererService rendererService;
    private final SearchService searchService;
    private final GenerationJobRepository jobRepository;
    private final GenerationEmitterManager emitterManager;
    private final ObjectMapper objectMapper;

    public GenerationWorkflowService(ContentGeneratorService contentGenerator,
                                     OutlineExtractor outlineExtractor,
                                     RequirementExtractor requirementExtractor,
                                     RequirementMapper requirementMapper,
                                     TavilySearchService tavilySearchService,
                                     DocumentRendererService rendererService,
                                     SearchService searchService,
                                     GenerationJobRepository jobRepository,
                                     GenerationEmitterManager emitterManager,
                                     ObjectMapper objectMapper) {
        this.contentGenerator = contentGenerator;
        this.outlineExtractor = outlineExtractor;
        this.requirementExtractor = requirementExtractor;
        this.requirementMapper = requirementMapper;
        this.tavilySearchService = tavilySearchService;
        this.rendererService = rendererService;
        this.searchService = searchService;
        this.jobRepository = jobRepository;
        this.emitterManager = emitterManager;
        this.objectMapper = objectMapper;
    }

    @Async("ingestionExecutor")
    public void execute(GenerationJob detachedJob, List<UUID> documentIds) {
        // @Async는 별도 스레드에서 실행되므로, 호출측 트랜잭션 커밋 후 DB에서 다시 조회 (template, user eager fetch)
        GenerationJob job = jobRepository.findByIdWithTemplate(detachedJob.getId())
                .orElseThrow(() -> new IllegalStateException("Job not found: " + detachedJob.getId()));
        try {
            // Phase 1: PLAN
            updateStatus(job, GenerationStatus.PLANNING);
            emitEvent(job, GenerationProgressEvent.status(GenerationStatus.PLANNING, "문서 구조를 설계하고 있습니다..."));

            List<String> ragContext = searchRelevantDocs(job, documentIds);
            String systemPrompt = job.getTemplate().getSystemPrompt();
            if (systemPrompt == null || systemPrompt.isBlank()) {
                systemPrompt = "당신은 전문 문서 작성자입니다. 명확하고 구조적인 문장을 사용하세요.";
            }

            DocumentOutline outline = contentGenerator.generateOutline(
                    job.getUserInput(), systemPrompt, ragContext);

            job.setOutline(toJson(outline));
            job.setTotalSections(outline.sections().size());
            jobRepository.save(job);
            log.info("Generation job {} - outline created with {} sections", job.getId(), outline.sections().size());

            // Phase 2: GENERATE
            updateStatus(job, GenerationStatus.GENERATING);
            List<SectionContent> sections = new ArrayList<>();

            for (int i = 0; i < outline.sections().size(); i++) {
                SectionPlan plan = outline.sections().get(i);
                job.setCurrentSection(i + 1);
                jobRepository.save(job);

                emitEvent(job, GenerationProgressEvent.progress(
                        i + 1, outline.sections().size(), plan.heading()));

                List<String> sectionContext = searchForSection(plan, documentIds);
                SectionContent content = generateWithRetry(plan, systemPrompt, sectionContext, sections);
                sections.add(content);

                if (log.isInfoEnabled()) {
                    log.info("Generation job {} - section {}/{} complete: {}",
                            job.getId(), i + 1, outline.sections().size(), plan.heading());
                }
            }

            job.setGeneratedSections(toJson(sections));
            jobRepository.save(job);

            // Phase 3: REVIEW — 향후 구현 (Plan 05)

            // Phase 4: RENDER
            updateStatus(job, GenerationStatus.RENDERING);
            emitEvent(job, GenerationProgressEvent.status(GenerationStatus.RENDERING, "최종 문서를 생성하고 있습니다..."));

            String outputPath = rendererService.render(job.getTemplate(), outline, sections, job.getUser().getId(), job.getId());
            job.setOutputFilePath(outputPath);

            updateStatus(job, GenerationStatus.COMPLETE);
            emitEvent(job, GenerationProgressEvent.complete("/api/generations/" + job.getId() + "/download"));
            log.info("Generation job {} - complete, output: {}", job.getId(), outputPath);

        } catch (Exception e) {
            log.error("Generation job {} failed", job.getId(), e);
            job.setErrorMessage(e.getMessage());
            updateStatus(job, GenerationStatus.FAILED);
            emitEvent(job, GenerationProgressEvent.error(e.getMessage()));
        }
    }

    /**
     * 위자드 Step 2: 고객문서에서 목차 추출 (async)
     */
    @Async("ingestionExecutor")
    public void extractOutline(UUID jobId, List<UUID> customerDocIds) {
        GenerationJob job = jobRepository.findByIdWithTemplate(jobId)
                .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
        try {
            emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, "고객 문서에서 목차를 추출하고 있습니다..."));

            // 고객문서 청크 수집
            List<String> customerChunks = searchService.searchDirect(
                    job.getUserInput() != null ? job.getUserInput().substring(0, Math.min(job.getUserInput().length(), 500)) : "사업 개요 목차 목적 범위",
                    customerDocIds).stream()
                    .map(ChunkSearchResult::contextContent)
                    .toList();

            if (customerChunks.isEmpty()) {
                // 고정 쿼리로 재시도
                for (String query : List.of("사업 개요 목적 배경", "기술 요구사항 평가기준", "수행 방안 일정 산출물")) {
                    customerChunks = searchService.searchDirect(query, customerDocIds).stream()
                            .map(ChunkSearchResult::contextContent)
                            .toList();
                    if (!customerChunks.isEmpty()) break;
                }
            }

            log.info("Generation job {} - collected {} customer chunks for outline extraction", jobId, customerChunks.size());

            // 목차 추출
            List<OutlineNode> outline = outlineExtractor.extract(customerChunks, job.getUserInput());
            job.setOutline(outlineExtractor.toJson(outline));
            job.setCurrentStep(2);
            job.setStepStatus("COMPLETE");
            job.setStatus(GenerationStatus.DRAFT);
            jobRepository.save(job);

            emitEvent(job, GenerationProgressEvent.complete("outline"));
            log.info("Generation job {} - outline extracted: {} top-level sections", jobId, outline.size());

        } catch (Exception e) {
            log.error("Generation job {} outline extraction failed", jobId, e);
            job.setErrorMessage(e.getMessage());
            job.setStepStatus("FAILED");
            job.setStatus(GenerationStatus.FAILED);
            jobRepository.save(job);
            emitEvent(job, GenerationProgressEvent.error(e.getMessage()));
        }
    }

    /**
     * 위자드 Step 3: 요구사항 추출 + 목차 매핑 (async)
     */
    @Async("ingestionExecutor")
    public void mapRequirements(UUID jobId, List<UUID> customerDocIds) {
        GenerationJob job = jobRepository.findByIdWithTemplate(jobId)
                .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
        try {
            job.setStatus(GenerationStatus.MAPPING);
            job.setCurrentStep(3);
            job.setStepStatus("PROCESSING");
            jobRepository.save(job);

            emitEvent(job, GenerationProgressEvent.status(GenerationStatus.MAPPING, "고객 문서에서 요구사항을 추출하고 있습니다..."));

            // 고객문서 청크 수집
            List<String> customerChunks = searchService.searchDirect(
                    job.getUserInput() != null ? job.getUserInput().substring(0, Math.min(job.getUserInput().length(), 500)) : "사업 요구사항 평가기준",
                    customerDocIds).stream()
                    .map(ChunkSearchResult::contextContent)
                    .toList();

            // 요구사항 추출 (질의서 모듈 재사용)
            List<Requirement> requirements = requirementExtractor.extract(customerChunks, job.getUserInput(),
                    msg -> emitEvent(job, GenerationProgressEvent.status(GenerationStatus.MAPPING, msg)));

            emitEvent(job, GenerationProgressEvent.status(GenerationStatus.MAPPING,
                    requirements.size() + "개 요구사항 추출 완료. 목차에 매핑하고 있습니다..."));
            log.info("Generation job {} - extracted {} requirements", jobId, requirements.size());

            // 목차 파싱
            List<OutlineNode> outline = outlineExtractor.fromJson(job.getOutline());

            // 요구사항 → 목차 매핑
            java.util.Map<String, List<String>> mapping = requirementMapper.map(outline, requirements);

            // 매핑 결과 저장 (requirements 목록도 함께 저장)
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("requirements", requirements);
            result.put("mapping", mapping);

            job.setRequirementMapping(toJson(result));
            job.setCurrentStep(3);
            job.setStepStatus("COMPLETE");
            job.setStatus(GenerationStatus.DRAFT);
            jobRepository.save(job);

            emitEvent(job, GenerationProgressEvent.complete("requirements"));
            log.info("Generation job {} - requirement mapping complete: {} keys", jobId, mapping.size());

        } catch (Exception e) {
            log.error("Generation job {} requirement mapping failed", jobId, e);
            job.setErrorMessage(e.getMessage());
            job.setStepStatus("FAILED");
            job.setStatus(GenerationStatus.FAILED);
            jobRepository.save(job);
            emitEvent(job, GenerationProgressEvent.error(e.getMessage()));
        }
    }

    /**
     * 위자드 Step 4: 요구사항 기반 섹션별 내용 생성 (async)
     */
    @Async("ingestionExecutor")
    public void generateWizardSections(UUID jobId, List<UUID> refDocIds, boolean includeWebSearch) {
        GenerationJob job = jobRepository.findByIdWithTemplate(jobId)
                .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
        try {
            job.setStatus(GenerationStatus.GENERATING);
            job.setCurrentStep(4);
            job.setStepStatus("PROCESSING");
            jobRepository.save(job);

            // 목차 파싱
            List<OutlineNode> outline = outlineExtractor.fromJson(job.getOutline());

            // 요구사항 매핑 파싱
            Map<String, List<String>> mapping = Map.of();
            List<Requirement> requirements = List.of();
            if (job.getRequirementMapping() != null) {
                try {
                    var parsed = objectMapper.readTree(job.getRequirementMapping());
                    requirements = objectMapper.readValue(
                            parsed.get("requirements").toString(),
                            new TypeReference<List<Requirement>>() {});
                    mapping = objectMapper.readValue(
                            parsed.get("mapping").toString(),
                            new TypeReference<Map<String, List<String>>>() {});
                } catch (Exception e) {
                    log.warn("Failed to parse requirement mapping: {}", e.getMessage());
                }
            }

            Map<String, Requirement> reqMap = new java.util.HashMap<>();
            for (Requirement r : requirements) reqMap.put(r.id(), r);

            // 리프 노드(최하위 목차) 수집
            List<LeafSection> leafSections = new ArrayList<>();
            collectLeafSections(outline, mapping, leafSections);

            job.setTotalSections(leafSections.size());
            jobRepository.save(job);

            String systemPrompt = job.getTemplate().getSystemPrompt();
            if (systemPrompt == null || systemPrompt.isBlank()) {
                systemPrompt = "당신은 전문 제안서 작성자입니다. 고객 요구사항을 정확히 반영하고, 구체적이며 설득력 있는 문서를 작성하세요.";
            }

            List<SectionContent> sections = new ArrayList<>();

            for (int i = 0; i < leafSections.size(); i++) {
                LeafSection leaf = leafSections.get(i);
                job.setCurrentSection(i + 1);
                jobRepository.save(job);

                emitEvent(job, GenerationProgressEvent.progress(i + 1, leafSections.size(), leaf.title));

                // 해당 섹션의 요구사항 텍스트
                String reqText = leaf.requirementIds.stream()
                        .map(reqMap::get)
                        .filter(java.util.Objects::nonNull)
                        .map(r -> "- [" + r.importance() + "] " + r.item() + ": " + r.description())
                        .collect(java.util.stream.Collectors.joining("\n"));

                // 참조문서 RAG 검색
                List<String> ragContext = List.of();
                if (!refDocIds.isEmpty()) {
                    String query = leaf.title + " " + reqText;
                    if (query.length() > 500) query = query.substring(0, 500);
                    ragContext = searchService.searchDirect(query, refDocIds).stream()
                            .map(ChunkSearchResult::contextContent)
                            .toList();
                }

                // 웹 검색
                List<String> webContext = List.of();
                if (includeWebSearch) {
                    String webQuery = leaf.title + " 제안서";
                    webContext = tavilySearchService.search(webQuery, 3);
                }

                // 섹션 생성 (재시도 포함)
                SectionContent content = null;
                for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                    try {
                        content = contentGenerator.generateSectionWithRequirements(
                                leaf.key, leaf.title, leaf.description, reqText,
                                systemPrompt, ragContext, webContext, sections);
                        break;
                    } catch (Exception e) {
                        if (attempt == MAX_RETRIES) throw e;
                        log.warn("Section generation retry {}/{} for '{}': {}", attempt + 1, MAX_RETRIES, leaf.title, e.getMessage());
                    }
                }
                sections.add(content);
                log.info("Generation job {} - wizard section {}/{} complete: {}", jobId, i + 1, leafSections.size(), leaf.title);
            }

            job.setGeneratedSections(toJson(sections));
            job.setCurrentStep(4);
            job.setStepStatus("COMPLETE");
            job.setStatus(GenerationStatus.READY);
            jobRepository.save(job);

            emitEvent(job, GenerationProgressEvent.complete("sections"));
            log.info("Generation job {} - all {} wizard sections complete", jobId, sections.size());

        } catch (Exception e) {
            log.error("Generation job {} wizard section generation failed", jobId, e);
            job.setErrorMessage(e.getMessage());
            job.setStepStatus("FAILED");
            job.setStatus(GenerationStatus.FAILED);
            jobRepository.save(job);
            emitEvent(job, GenerationProgressEvent.error(e.getMessage()));
        }
    }

    private record LeafSection(String key, String title, String description, List<String> requirementIds) {}

    private void collectLeafSections(List<OutlineNode> nodes, Map<String, List<String>> mapping, List<LeafSection> result) {
        for (OutlineNode node : nodes) {
            if (node.children().isEmpty()) {
                result.add(new LeafSection(node.key(), node.title(), node.description(), mapping.getOrDefault(node.key(), List.of())));
            } else {
                collectLeafSections(node.children(), mapping, result);
            }
        }
    }

    private SectionContent generateWithRetry(SectionPlan plan, String systemPrompt,
                                             List<String> ragContext,
                                             List<SectionContent> previousSections) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return contentGenerator.generateSection(plan, systemPrompt, ragContext, previousSections);
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    throw e;
                }
                log.warn("Section generation retry {}/{} for '{}': {}",
                        attempt + 1, MAX_RETRIES, plan.heading(), e.getMessage());
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private List<String> searchRelevantDocs(GenerationJob job, List<UUID> documentIds) {
        String query = job.getUserInput();
        if (query.length() > 500) {
            query = query.substring(0, 500);
        }
        return searchService.search(query, documentIds).stream()
                .map(ChunkSearchResult::contextContent)
                .toList();
    }

    private List<String> searchForSection(SectionPlan plan, List<UUID> documentIds) {
        String query = plan.heading() + " " + String.join(" ", plan.keyPoints());
        return searchService.search(query, documentIds).stream()
                .map(ChunkSearchResult::contextContent)
                .toList();
    }

    private void updateStatus(GenerationJob job, GenerationStatus status) {
        job.setStatus(status);
        jobRepository.save(job);
    }

    private void emitEvent(GenerationJob job, GenerationProgressEvent event) {
        SseEmitter emitter = emitterManager.get(job.getId());
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(event.eventType())
                    .data(event));
            if ("complete".equals(event.eventType()) || "error".equals(event.eventType())) {
                emitter.complete();
            }
        } catch (IOException e) {
            log.debug("Failed to send SSE event for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RagException("Failed to serialize to JSON", e);
        }
    }
}
