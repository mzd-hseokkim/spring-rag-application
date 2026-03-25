package com.example.rag.generation.workflow;

import com.example.rag.common.RagException;
import com.example.rag.document.DocumentChunk;
import com.example.rag.document.DocumentChunkRepository;
import com.example.rag.generation.GenerationEmitterManager;
import com.example.rag.generation.GenerationJob;
import com.example.rag.generation.GenerationJobRepository;
import com.example.rag.generation.GenerationStatus;
import com.example.rag.common.PromptLoader;
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
    private final DocumentChunkRepository chunkRepository;
    private final PromptLoader promptLoader;
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
                                     DocumentChunkRepository chunkRepository,
                                     PromptLoader promptLoader,
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
        this.chunkRepository = chunkRepository;
        this.promptLoader = promptLoader;
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
                        i + 1, outline.sections().size(), plan.heading(), plan.key()));

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
     * 위자드 Step 2: 요구사항 추출 → 목차 추출 (async)
     * 요구사항을 먼저 추출하고, 그 결과를 목차 추출 프롬프트에 전달하여
     * 모든 요구사항이 목차에 반영되도록 한다.
     */
    @Async("ingestionExecutor")
    public void extractOutline(UUID jobId, List<UUID> customerDocIds) {
        GenerationJob job = jobRepository.findByIdWithTemplate(jobId)
                .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
        try {
            // Phase 1: 고객문서 전체 청크 로드
            emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, "고객 문서를 읽고 있습니다..."));
            List<String> customerChunks = new ArrayList<>();
            for (UUID docId : customerDocIds) {
                List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
                for (DocumentChunk chunk : chunks) {
                    customerChunks.add(chunk.getContent());
                }
            }
            log.info("Generation job {} - loaded {} total chunks from {} customer documents",
                    jobId, customerChunks.size(), customerDocIds.size());

            // Phase 2: 요구사항 — 이미 추출된 것이 있으면 재사용, 없으면 추출
            List<Requirement> requirements = List.of();
            if (job.getRequirementMapping() != null) {
                try {
                    var parsed = objectMapper.readTree(job.getRequirementMapping());
                    if (parsed.has("requirements") && parsed.get("requirements").size() > 0) {
                        requirements = objectMapper.readValue(
                                parsed.get("requirements").toString(),
                                new TypeReference<List<Requirement>>() {});
                        log.info("Generation job {} - reusing {} existing requirements", jobId, requirements.size());
                        emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING,
                                "기존 요구사항 " + requirements.size() + "개를 사용합니다."));
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse existing requirements: {}", e.getMessage());
                }
            }

            if (requirements.isEmpty()) {
                emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, "요구사항을 추출하고 있습니다..."));
                requirements = requirementExtractor.extract(customerChunks, job.getUserInput(),
                        msg -> emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, msg)),
                        partialReqs -> emitRequirements(job, partialReqs));

                // 요구사항을 job에 미리 저장 (Step 3에서 재활용)
                var reqData = new java.util.LinkedHashMap<String, Object>();
                reqData.put("requirements", requirements);
                reqData.put("mapping", java.util.Map.of());
                job.setRequirementMapping(toJson(reqData));
            }

            log.info("Generation job {} - {} requirements for outline extraction", jobId, requirements.size());

            // 요구사항을 텍스트 요약으로 변환 (목차 프롬프트에 전달)
            String requirementsSummary = requirements.stream()
                    .map(r -> "- [" + r.id() + "] [" + r.importance() + "] " + r.item() + ": " + r.description())
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (requirementsSummary.isBlank()) {
                requirementsSummary = "없음 (요구사항이 명시되지 않은 문서)";
            }

            // Phase 3: 목차 추출 (요구사항 포함 — 많으면 map-reduce로 자동 분할)
            emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, "요구사항을 반영하여 목차를 구성하고 있습니다..."));
            List<OutlineNode> outline = outlineExtractor.extract(customerChunks, job.getUserInput(),
                    requirementsSummary, requirements,
                    msg -> emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, msg)));
            job.setOutline(outlineExtractor.toJson(outline));
            job.setCurrentStep(2);
            job.setStepStatus("COMPLETE");
            job.setStatus(GenerationStatus.DRAFT);
            jobRepository.save(job);

            emitEvent(job, GenerationProgressEvent.complete("outline"));
            log.info("Generation job {} - outline extracted: {} top-level sections (with {} requirements)",
                    jobId, outline.size(), requirements.size());

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
     * 위자드 Step 3: 요구사항 → 목차 매핑 (async)
     * Step 2에서 이미 추출된 요구사항이 있으면 재추출 없이 매핑만 수행한다.
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

            // Step 2에서 미리 추출된 요구사항이 있는지 확인
            List<Requirement> requirements = List.of();
            if (job.getRequirementMapping() != null) {
                try {
                    var parsed = objectMapper.readTree(job.getRequirementMapping());
                    if (parsed.has("requirements") && parsed.get("requirements").size() > 0) {
                        requirements = objectMapper.readValue(
                                parsed.get("requirements").toString(),
                                new TypeReference<List<Requirement>>() {});
                        log.info("Generation job {} - reusing {} requirements from Step 2", jobId, requirements.size());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse existing requirements: {}", e.getMessage());
                }
            }

            // 기존 요구사항이 없으면 전체 문서에서 추출
            if (requirements.isEmpty()) {
                emitEvent(job, GenerationProgressEvent.status(GenerationStatus.MAPPING, "고객 문서 전체를 읽고 요구사항을 추출하고 있습니다..."));
                List<String> customerChunks = new ArrayList<>();
                for (UUID docId : customerDocIds) {
                    List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
                    for (DocumentChunk chunk : chunks) {
                        customerChunks.add(chunk.getContent());
                    }
                }
                log.info("Generation job {} - loaded {} total chunks for requirement extraction", jobId, customerChunks.size());

                requirements = requirementExtractor.extract(customerChunks, job.getUserInput(),
                        msg -> emitEvent(job, GenerationProgressEvent.status(GenerationStatus.MAPPING, msg)),
                        partialReqs -> emitRequirements(job, partialReqs));
            }

            emitEvent(job, GenerationProgressEvent.status(GenerationStatus.MAPPING,
                    requirements.size() + "개 요구사항을 목차에 매핑하고 있습니다..."));
            log.info("Generation job {} - mapping {} requirements to outline", jobId, requirements.size());

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
        generateWizardSections(jobId, refDocIds, includeWebSearch, List.of());
    }

    @Async("ingestionExecutor")
    public void generateWizardSections(UUID jobId, List<UUID> refDocIds, boolean includeWebSearch, List<String> filterKeys) {
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

            // 리프 노드(최하위 목차) 수집 + 자연수 정렬
            List<LeafSection> leafSections = new ArrayList<>();
            collectLeafSections(outline, mapping, leafSections);
            leafSections.sort(java.util.Comparator.comparing(LeafSection::key, GenerationWorkflowService::compareKeys));

            // 선택된 섹션만 필터링 (filterKeys가 비어있으면 전체)
            if (!filterKeys.isEmpty()) {
                java.util.Set<String> allowed = new java.util.HashSet<>(filterKeys);
                leafSections = leafSections.stream()
                        .filter(leaf -> allowed.contains(leaf.key()) || filterKeys.stream().anyMatch(fk -> leaf.key().startsWith(fk + ".")))
                        .toList();
                log.info("Filtered to {} leaf sections by {} keys", leafSections.size(), filterKeys.size());
            }

            job.setTotalSections(leafSections.size());
            jobRepository.save(job);

            String systemPrompt = job.getTemplate().getSystemPrompt();
            if (systemPrompt == null || systemPrompt.isBlank()) {
                systemPrompt = "당신은 전문 제안서 작성자입니다. 고객 요구사항을 정확히 반영하고, 구체적이며 설득력 있는 문서를 작성하세요.";
            }

            // 기존에 생성된 섹션이 있으면 복원 (이어서 생성)
            List<SectionContent> sections = new ArrayList<>();
            java.util.Set<String> existingKeys = new java.util.HashSet<>();
            if (job.getGeneratedSections() != null) {
                try {
                    List<SectionContent> existing = objectMapper.readValue(job.getGeneratedSections(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<SectionContent>>() {});
                    sections.addAll(existing);
                    existing.forEach(s -> existingKeys.add(s.key()));
                    log.info("Generation job {} - resuming with {} existing sections", jobId, existing.size());
                } catch (Exception e) {
                    log.warn("Failed to parse existing sections, starting fresh: {}", e.getMessage());
                }
            }

            for (int i = 0; i < leafSections.size(); i++) {
                LeafSection leaf = leafSections.get(i);
                job.setCurrentSection(i + 1);
                jobRepository.save(job);

                // 이미 생성된 섹션은 스킵
                if (existingKeys.contains(leaf.key)) {
                    emitEvent(job, GenerationProgressEvent.progress(i + 1, leafSections.size(), leaf.title + " (기존)", leaf.key));
                    continue;
                }

                emitEvent(job, GenerationProgressEvent.progress(i + 1, leafSections.size(), leaf.title, leaf.key));

                // 해당 섹션의 요구사항 텍스트
                String reqText = leaf.requirementIds.stream()
                        .map(reqMap::get)
                        .filter(java.util.Objects::nonNull)
                        .map(r -> "- [" + r.importance() + "] " + r.item() + ": " + r.description())
                        .collect(java.util.stream.Collectors.joining("\n"));

                // LLM으로 검색어 생성
                List<String> searchQueries = generateSearchQueries(leaf, reqText);
                log.debug("Section '{}' search queries: {}", leaf.title, searchQueries);

                // 참조문서 RAG 검색 — 생성된 검색어로
                List<String> ragContext = List.of();
                List<String> sources = new ArrayList<>();
                if (!refDocIds.isEmpty()) {
                    java.util.LinkedHashSet<String> uniqueChunks = new java.util.LinkedHashSet<>();
                    List<String> ragSourceList = new ArrayList<>();
                    for (String query : searchQueries) {
                        List<ChunkSearchResult> results = searchService.searchDirect(query, refDocIds);
                        results.forEach(r -> {
                            uniqueChunks.add(r.contextContent());
                            ragSourceList.add("[문서] " + r.filename() + " (청크 #" + r.chunkIndex() + ")");
                        });
                    }
                    ragContext = new ArrayList<>(uniqueChunks);
                    ragSourceList.stream().distinct().forEach(sources::add);
                }

                // 웹 검색 — 생성된 검색어로
                List<String> webContext = List.of();
                if (includeWebSearch) {
                    List<String> allWebResults = new ArrayList<>();
                    for (String query : searchQueries) {
                        List<String> results = tavilySearchService.search(query, 2);
                        allWebResults.addAll(results);
                    }
                    webContext = allWebResults.stream().distinct().toList();
                    webContext.stream()
                            .map(this::formatWebSource)
                            .forEach(sources::add);
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
                // key 강제 덮어씌움 + 실제 출처(sources) 추가
                content = new SectionContent(
                        leaf.key, content.title(), content.content(),
                        content.highlights(), content.tables(), content.references(),
                        content.layoutType(), content.layoutData(),
                        content.governingMessage(), content.visualGuide(), sources);
                sections.add(content);
                // 중간 저장 — 프론트엔드가 progress 이벤트마다 조회하여 완료된 섹션을 표시
                job.setGeneratedSections(toJson(sections));
                jobRepository.save(job);
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

    /**
     * 위자드 Step 4: 단일 섹션 재생성 (async)
     */
    @Async("ingestionExecutor")
    public void regenerateSection(UUID jobId, String sectionKey, List<UUID> refDocIds, boolean includeWebSearch) {
        GenerationJob job = jobRepository.findByIdWithTemplate(jobId)
                .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
        try {
            emitEvent(job, GenerationProgressEvent.status(GenerationStatus.GENERATING, sectionKey + " 섹션을 재생성하고 있습니다..."));

            // 기존 섹션 목록 파싱
            List<SectionContent> sections;
            try {
                sections = new ArrayList<>(objectMapper.readValue(job.getGeneratedSections(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<SectionContent>>() {}));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse existing sections", e);
            }

            // 대상 섹션 찾기
            int targetIndex = -1;
            for (int i = 0; i < sections.size(); i++) {
                if (sectionKey.equals(sections.get(i).key())) {
                    targetIndex = i;
                    break;
                }
            }
            if (targetIndex < 0) {
                throw new IllegalArgumentException("Section not found: " + sectionKey);
            }

            // 요구사항 매핑에서 해당 섹션의 요구사항 추출
            String reqText = "";
            if (job.getRequirementMapping() != null) {
                try {
                    var parsed = objectMapper.readTree(job.getRequirementMapping());
                    var reqList = objectMapper.readValue(parsed.get("requirements").toString(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<com.example.rag.questionnaire.workflow.Requirement>>() {});
                    var mapping = objectMapper.readValue(parsed.get("mapping").toString(),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, List<String>>>() {});
                    Map<String, com.example.rag.questionnaire.workflow.Requirement> reqMap = new java.util.HashMap<>();
                    for (var r : reqList) reqMap.put(r.id(), r);

                    List<String> reqIds = mapping.getOrDefault(sectionKey, List.of());
                    reqText = reqIds.stream()
                            .map(reqMap::get)
                            .filter(java.util.Objects::nonNull)
                            .map(r -> "- [" + r.importance() + "] " + r.item() + ": " + r.description())
                            .collect(java.util.stream.Collectors.joining("\n"));
                } catch (Exception e) {
                    log.warn("Failed to extract requirements for section {}: {}", sectionKey, e.getMessage());
                }
            }

            SectionContent old = sections.get(targetIndex);
            String systemPrompt = job.getTemplate().getSystemPrompt();
            if (systemPrompt == null || systemPrompt.isBlank()) {
                systemPrompt = "당신은 전문 제안서 작성자입니다.";
            }

            // RAG 검색
            List<String> ragContext = List.of();
            if (!refDocIds.isEmpty()) {
                String query = old.title() + " " + reqText;
                if (query.length() > 500) query = query.substring(0, 500);
                ragContext = searchService.searchDirect(query, refDocIds).stream()
                        .map(ChunkSearchResult::contextContent).toList();
            }

            // 웹 검색
            List<String> webContext = List.of();
            if (includeWebSearch) {
                webContext = tavilySearchService.search(old.title() + " 제안서", 3);
            }

            // 이전 섹션 컨텍스트 (대상 앞까지)
            List<SectionContent> previous = sections.subList(0, targetIndex);

            // 재생성
            SectionContent regenerated = contentGenerator.generateSectionWithRequirements(
                    old.key(), old.title(), "", reqText, systemPrompt, ragContext, webContext, previous);

            // key 강제 덮어씌움 + sources 추가
            List<String> sources = new ArrayList<>();
            if (!refDocIds.isEmpty()) {
                searchService.searchDirect(old.title(), refDocIds).stream()
                        .map(r -> "[문서] " + r.filename() + " (청크 #" + r.chunkIndex() + ")")
                        .distinct().forEach(sources::add);
            }
            if (includeWebSearch) {
                webContext.stream()
                        .map(this::formatWebSource)
                        .forEach(sources::add);
            }
            regenerated = new SectionContent(
                    sectionKey, regenerated.title(), regenerated.content(),
                    regenerated.highlights(), regenerated.tables(), regenerated.references(),
                    regenerated.layoutType(), regenerated.layoutData(),
                    regenerated.governingMessage(), regenerated.visualGuide(), sources);

            sections.set(targetIndex, regenerated);
            job.setGeneratedSections(toJson(sections));
            jobRepository.save(job);

            emitEvent(job, GenerationProgressEvent.complete("section-regenerated"));
            log.info("Generation job {} - section '{}' regenerated", jobId, sectionKey);

        } catch (Exception e) {
            log.error("Generation job {} section '{}' regeneration failed", jobId, sectionKey, e);
            emitEvent(job, GenerationProgressEvent.error(e.getMessage()));
        }
    }

    /**
     * 웹 검색 결과 텍스트에서 URL을 추출하여 출처 형식으로 변환.
     * 입력: "[제목] 내용... (출처: https://example.com)"
     * 출력: "[웹] [제목] 내용 요약... (https://example.com)"
     */
    private String formatWebSource(String webResult) {
        // URL 추출
        java.util.regex.Matcher urlMatcher = java.util.regex.Pattern
                .compile("(https?://[^\\s)]+)").matcher(webResult);
        String url = urlMatcher.find() ? urlMatcher.group(1) : "";
        log.debug("formatWebSource input ({}chars): {}", webResult.length(),
                webResult.substring(0, Math.min(webResult.length(), 200)));
        log.debug("formatWebSource extracted URL: '{}'", url);

        // 제목 추출 (첫 번째 [...] 부분)
        String title = "";
        java.util.regex.Matcher titleMatcher = java.util.regex.Pattern
                .compile("^\\[([^]]+)]").matcher(webResult);
        if (titleMatcher.find()) {
            title = titleMatcher.group(1);
        }

        if (!url.isEmpty()) {
            return "[웹] " + (!title.isEmpty() ? "[" + title + "] " : "") + url;
        }
        // URL이 없으면 텍스트 요약
        String summary = webResult.length() > 100 ? webResult.substring(0, 100) + "..." : webResult;
        return "[웹] " + summary;
    }

    private record LeafSection(String key, String title, String description, List<String> requirementIds) {}

    private List<String> generateSearchQueries(LeafSection leaf, String reqText) {
        try {
            var client = contentGenerator.getModelClient();
            String prompt = promptLoader.load("generation-search-query.txt");

            String desc = leaf.description != null ? leaf.description : "";
            String reqs = reqText.isBlank() ? "없음" : reqText;

            String content = client.prompt()
                    .user(u -> u.text(prompt)
                            .param("title", leaf.title)
                            .param("description", desc)
                            .param("requirements", reqs))
                    .call()
                    .content();

            if (content == null || content.isBlank()) {
                return List.of(leaf.title);
            }
            List<String> queries = java.util.Arrays.stream(content.trim().split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .limit(3)
                    .toList();
            return queries.isEmpty() ? List.of(leaf.title) : queries;
        } catch (Exception e) {
            log.warn("Failed to generate search queries for '{}': {}", leaf.title, e.getMessage());
            return List.of(leaf.title);
        }
    }

    /** "1.2.10" 같은 계층 번호를 자연수 순서로 비교 */
    private static int compareKeys(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length ? parseSegment(pa[i]) : -1;
            int nb = i < pb.length ? parseSegment(pb[i]) : -1;
            if (na != nb) return Integer.compare(na, nb);
        }
        return 0;
    }

    private static int parseSegment(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }

    private void collectLeafSections(List<OutlineNode> nodes, Map<String, List<String>> mapping, List<LeafSection> result) {
        collectLeafSections(nodes, mapping, result, List.of());
    }

    private void collectLeafSections(List<OutlineNode> nodes, Map<String, List<String>> mapping,
                                      List<LeafSection> result, List<String> inheritedReqIds) {
        for (OutlineNode node : nodes) {
            // 이 노드에 직접 매핑된 요구사항 + 부모에서 상속받은 요구사항
            List<String> ownReqIds = mapping.getOrDefault(node.key(), List.of());
            List<String> combined = new ArrayList<>(inheritedReqIds);
            combined.addAll(ownReqIds);

            if (node.children().isEmpty()) {
                // 리프 노드 — 자신 + 상속받은 요구사항 모두 포함
                result.add(new LeafSection(node.key(), node.title(), node.description(), combined));
            } else {
                // 중간 노드 — 자신의 요구사항을 자식에게 전달
                collectLeafSections(node.children(), mapping, result, combined);
            }
        }
    }

    /**
     * 위자드 Step 5: 생성된 섹션들을 HTML로 렌더링 (async)
     */
    @Async("ingestionExecutor")
    public void renderWizardDocument(UUID jobId) {
        GenerationJob job = jobRepository.findByIdWithTemplate(jobId)
                .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
        try {
            job.setStatus(GenerationStatus.RENDERING);
            job.setCurrentStep(5);
            job.setStepStatus("PROCESSING");
            jobRepository.save(job);

            emitEvent(job, GenerationProgressEvent.status(GenerationStatus.RENDERING, "최종 문서를 렌더링하고 있습니다..."));

            // 목차에서 DocumentOutline 구성
            List<OutlineNode> outlineNodes = outlineExtractor.fromJson(job.getOutline());
            String title = outlineNodes.isEmpty() ? "제안서" : outlineNodes.get(0).title();
            DocumentOutline outline = new DocumentOutline(title, "", buildSectionPlans(outlineNodes));

            // 섹션 파싱
            List<SectionContent> sections;
            try {
                sections = objectMapper.readValue(job.getGeneratedSections(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<SectionContent>>() {});
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse generated sections", e);
            }

            // HTML 렌더링
            String outputPath = rendererService.render(job.getTemplate(), outline, sections, job.getUser().getId(), job.getId());
            job.setOutputFilePath(outputPath);
            job.setCurrentStep(5);
            job.setStepStatus("COMPLETE");
            job.setStatus(GenerationStatus.COMPLETE);
            jobRepository.save(job);

            emitEvent(job, GenerationProgressEvent.complete("/api/generations/" + job.getId() + "/download"));
            log.info("Generation job {} - wizard rendering complete, output: {}", jobId, outputPath);

        } catch (Exception e) {
            log.error("Generation job {} wizard rendering failed", jobId, e);
            job.setErrorMessage(e.getMessage());
            job.setStepStatus("FAILED");
            job.setStatus(GenerationStatus.FAILED);
            jobRepository.save(job);
            emitEvent(job, GenerationProgressEvent.error(e.getMessage()));
        }
    }

    private List<SectionPlan> buildSectionPlans(List<OutlineNode> nodes) {
        List<SectionPlan> plans = new ArrayList<>();
        for (OutlineNode node : nodes) {
            if (node.children().isEmpty()) {
                plans.add(new SectionPlan(node.key(), node.title(), node.description(), List.of(), 500));
            } else {
                for (OutlineNode child : node.children()) {
                    plans.add(new SectionPlan(child.key(), child.title(), child.description(), List.of(), 500));
                }
            }
        }
        return plans;
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

    private void emitRequirements(GenerationJob job, List<Requirement> requirements) {
        SseEmitter emitter = emitterManager.get(job.getId());
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("requirements")
                    .data(requirements));
        } catch (IllegalStateException e) {
            log.debug("SSE emitter already completed for job {}: {}", job.getId(), e.getMessage());
            emitterManager.remove(job.getId());
        } catch (IOException e) {
            log.debug("SSE client disconnected for job {}: {}", job.getId(), e.getMessage());
            emitterManager.remove(job.getId());
        }
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
        } catch (IllegalStateException e) {
            // emitter가 이미 complete된 경우 — 클라이언트 연결 해제 시 발생
            log.debug("SSE emitter already completed for job {}: {}", job.getId(), e.getMessage());
            emitterManager.remove(job.getId());
        } catch (IOException e) {
            // 클라이언트가 연결을 끊은 경우 — emitter 제거하여 이후 전송 시도 방지
            log.debug("SSE client disconnected for job {}: {}", job.getId(), e.getMessage());
            emitterManager.remove(job.getId());
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
