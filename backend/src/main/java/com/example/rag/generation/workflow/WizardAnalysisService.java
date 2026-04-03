package com.example.rag.generation.workflow;

import com.example.rag.document.DocumentChunk;
import com.example.rag.document.DocumentChunkRepository;
import com.example.rag.dashboard.GenerationTraceEntity;
import com.example.rag.dashboard.GenerationTraceService;
import com.example.rag.generation.GenerationJob;
import com.example.rag.generation.GenerationJobRepository;
import com.example.rag.generation.GenerationStatus;
import com.example.rag.generation.dto.GenerationProgressEvent;
import com.example.rag.generation.dto.OutlineNode;
import com.example.rag.model.TokenRecordingContext;
import com.example.rag.questionnaire.workflow.Requirement;
import com.example.rag.questionnaire.workflow.RequirementCacheService;
import com.example.rag.questionnaire.workflow.RequirementExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WizardAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(WizardAnalysisService.class);
    private static final String JOB_NOT_FOUND = "Job not found: ";
    private static final String STEP_STATUS_PROCESSING = "PROCESSING";
    private static final String STEP_STATUS_COMPLETE = "COMPLETE";
    private static final String STEP_STATUS_FAILED = "FAILED";
    private static final String FIELD_REQUIREMENTS = "requirements";
    private static final String FIELD_MAPPING = "mapping";
    private static final String MSG_CACHED_REQUIREMENTS = "개를 사용합니다.";

    private final OutlineExtractor outlineExtractor;
    private final RequirementExtractor requirementExtractor;
    private final RequirementCacheService requirementCache;
    private final RequirementMapper requirementMapper;
    private final DocumentChunkRepository chunkRepository;
    private final GenerationJobRepository jobRepository;
    private final GenerationDataParser dataParser;
    private final GenerationTraceService traceService;
    private final WorkflowEventEmitter eventEmitter;

    public WizardAnalysisService(OutlineExtractor outlineExtractor,
                                 RequirementExtractor requirementExtractor,
                                 RequirementCacheService requirementCache,
                                 RequirementMapper requirementMapper,
                                 DocumentChunkRepository chunkRepository,
                                 GenerationJobRepository jobRepository,
                                 GenerationDataParser dataParser,
                                 GenerationTraceService traceService,
                                 WorkflowEventEmitter eventEmitter) {
        this.outlineExtractor = outlineExtractor;
        this.requirementExtractor = requirementExtractor;
        this.requirementCache = requirementCache;
        this.requirementMapper = requirementMapper;
        this.chunkRepository = chunkRepository;
        this.jobRepository = jobRepository;
        this.dataParser = dataParser;
        this.traceService = traceService;
        this.eventEmitter = eventEmitter;
    }

    /**
     * 위자드 Step 2: 요구사항 추출 → 목차 추출 (async)
     * 요구사항을 먼저 추출하고, 그 결과를 목차 추출 프롬프트에 전달하여
     * 모든 요구사항이 목차에 반영되도록 한다.
     */
    @Async("ingestionExecutor")
    public void extractOutline(UUID jobId, List<UUID> customerDocIds) {
        GenerationJob job = jobRepository.findByIdWithTemplate(jobId)
                .orElseThrow(() -> new IllegalStateException(JOB_NOT_FOUND + jobId));
        TokenRecordingContext.setUserId(job.getUser().getId());
        GenerationTraceEntity trace = traceService.start(jobId, "GENERATION", "EXTRACT_OUTLINE");
        try {
            // Phase 1: 고객문서 전체 청크 로드
            eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, "고객 문서를 읽고 있습니다..."));
            List<String> customerChunks = loadCustomerChunks(customerDocIds);
            log.info("Generation job {} - loaded {} total chunks from {} customer documents",
                    jobId, customerChunks.size(), customerDocIds.size());

            // Phase 1.5: 권장 목차 감지 (키워드 필터링 → LLM 추출)
            eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, "권장 목차를 확인하고 있습니다..."));
            String recommendedOutline = outlineExtractor.detectRecommendedOutline(customerDocIds);
            if (recommendedOutline != null) {
                log.info("Generation job {} - recommended outline detected ({}chars)", jobId, recommendedOutline.length());
                eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, "발주처 권장 목차를 발견했습니다. 이를 기준으로 목차를 구성합니다."));
            } else {
                log.info("Generation job {} - no recommended outline found, using standard structure", jobId);
            }

            // Phase 2: 요구사항 — 이미 추출된 것이 있으면 재사용, 없으면 추출
            List<Requirement> requirements = dataParser.parseRequirementsFromMapping(job.getRequirementMapping());
            if (!requirements.isEmpty()) {
                log.info("Generation job {} - reusing {} existing requirements", jobId, requirements.size());
                eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING,
                        "기존 요구사항 " + requirements.size() + MSG_CACHED_REQUIREMENTS));
            }

            if (requirements.isEmpty()) {
                // 공유 캐시에서 확인
                requirements = requirementCache.get(customerDocIds);
                if (!requirements.isEmpty()) {
                    eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING,
                            "캐시된 요구사항 " + requirements.size() + MSG_CACHED_REQUIREMENTS));
                    log.info("Generation job {} - using {} cached requirements", jobId, requirements.size());
                    eventEmitter.emitRequirements(job, requirements);
                } else {
                    eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, "요구사항을 추출하고 있습니다..."));
                    requirements = requirementExtractor.extract(customerChunks, job.getUserInput(),
                            msg -> eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, msg)),
                            partialReqs -> eventEmitter.emitRequirements(job, partialReqs));
                    requirementCache.put(customerDocIds, requirements);
                }

                // 요구사항을 job에 미리 저장 (Step 3에서 재활용)
                var reqData = new java.util.LinkedHashMap<String, Object>();
                reqData.put(FIELD_REQUIREMENTS, requirements);
                reqData.put(FIELD_MAPPING, java.util.Map.of());
                job.setRequirementMapping(dataParser.toJson(reqData));
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
            eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, "요구사항을 반영하여 목차를 구성하고 있습니다..."));
            List<OutlineNode> outline = outlineExtractor.extract(customerChunks, job.getUserInput(),
                    requirementsSummary, requirements,
                    msg -> eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.ANALYZING, msg)),
                    recommendedOutline);
            job.setOutline(outlineExtractor.toJson(outline));
            job.setCurrentStep(2);
            job.setStepStatus(STEP_STATUS_COMPLETE);
            job.setStatus(GenerationStatus.DRAFT);
            jobRepository.save(job);

            traceService.complete(trace);
            eventEmitter.emitEvent(job, GenerationProgressEvent.complete("outline"));
            log.info("Generation job {} - outline extracted: {} top-level sections (with {} requirements)",
                    jobId, outline.size(), requirements.size());

        } catch (Exception e) {
            log.error("Generation job {} outline extraction failed", jobId, e);
            traceService.fail(trace, e.getMessage());
            job.setErrorMessage(e.getMessage());
            job.setStepStatus(STEP_STATUS_FAILED);
            job.setStatus(GenerationStatus.FAILED);
            jobRepository.save(job);
            eventEmitter.emitEvent(job, GenerationProgressEvent.error(e.getMessage()));
        } finally {
            TokenRecordingContext.clear();
        }
    }

    /**
     * 위자드 Step 3: 요구사항 → 목차 매핑 (async)
     * Step 2에서 이미 추출된 요구사항이 있으면 재추출 없이 매핑만 수행한다.
     */
    @Async("ingestionExecutor")
    public void mapRequirements(UUID jobId, List<UUID> customerDocIds) {
        GenerationJob job = jobRepository.findByIdWithTemplate(jobId)
                .orElseThrow(() -> new IllegalStateException(JOB_NOT_FOUND + jobId));
        TokenRecordingContext.setUserId(job.getUser().getId());
        GenerationTraceEntity trace = traceService.start(jobId, "GENERATION", "MAP_REQUIREMENTS");
        try {
            job.setStatus(GenerationStatus.MAPPING);
            job.setCurrentStep(3);
            job.setStepStatus(STEP_STATUS_PROCESSING);
            jobRepository.save(job);

            // Step 2에서 미리 추출된 요구사항이 있는지 확인
            List<Requirement> requirements = dataParser.parseRequirementsFromMapping(job.getRequirementMapping());
            if (!requirements.isEmpty()) {
                log.info("Generation job {} - reusing {} requirements from Step 2", jobId, requirements.size());
            }

            // 기존 요구사항이 없으면 공유 캐시 확인 → 없으면 추출
            if (requirements.isEmpty()) {
                requirements = requirementCache.get(customerDocIds);
                if (!requirements.isEmpty()) {
                    eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.MAPPING,
                            "캐시된 요구사항 " + requirements.size() + MSG_CACHED_REQUIREMENTS));
                    log.info("Generation job {} - using {} cached requirements for mapping", jobId, requirements.size());
                    eventEmitter.emitRequirements(job, requirements);
                } else {
                    eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.MAPPING, "고객 문서 전체를 읽고 요구사항을 추출하고 있습니다..."));
                    List<String> customerChunks = loadCustomerChunks(customerDocIds);
                    log.info("Generation job {} - loaded {} total chunks for requirement extraction", jobId, customerChunks.size());

                    requirements = requirementExtractor.extract(customerChunks, job.getUserInput(),
                            msg -> eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.MAPPING, msg)),
                            partialReqs -> eventEmitter.emitRequirements(job, partialReqs));
                    requirementCache.put(customerDocIds, requirements);
                }
            }

            eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.MAPPING,
                    requirements.size() + "개 요구사항을 목차에 매핑하고 있습니다..."));
            log.info("Generation job {} - mapping {} requirements to outline", jobId, requirements.size());

            // 목차 파싱
            List<OutlineNode> outline = outlineExtractor.fromJson(job.getOutline());

            // 요구사항 → 목차 매핑
            java.util.Map<String, List<String>> mapping = requirementMapper.map(outline, requirements);

            // 매핑 결과 저장 (requirements 목록도 함께 저장)
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put(FIELD_REQUIREMENTS, requirements);
            result.put(FIELD_MAPPING, mapping);

            job.setRequirementMapping(dataParser.toJson(result));
            job.setCurrentStep(3);
            job.setStepStatus(STEP_STATUS_COMPLETE);
            job.setStatus(GenerationStatus.DRAFT);
            jobRepository.save(job);

            traceService.complete(trace);
            eventEmitter.emitEvent(job, GenerationProgressEvent.complete(FIELD_REQUIREMENTS));
            log.info("Generation job {} - requirement mapping complete: {} keys", jobId, mapping.size());

        } catch (Exception e) {
            log.error("Generation job {} requirement mapping failed", jobId, e);
            traceService.fail(trace, e.getMessage());
            job.setErrorMessage(e.getMessage());
            job.setStepStatus(STEP_STATUS_FAILED);
            job.setStatus(GenerationStatus.FAILED);
            jobRepository.save(job);
            eventEmitter.emitEvent(job, GenerationProgressEvent.error(e.getMessage()));
        } finally {
            TokenRecordingContext.clear();
        }
    }

    /**
     * 미배치 요구사항을 위한 새 목차 섹션 생성 (동기)
     */
    public void generateSectionsForUnmapped(UUID jobId) {
        GenerationJob job = jobRepository.findByIdWithTemplate(jobId)
                .orElseThrow(() -> new IllegalStateException(JOB_NOT_FOUND + jobId));

        List<OutlineNode> outline = outlineExtractor.fromJson(job.getOutline());
        List<Requirement> requirements = dataParser.parseRequirementsFromMapping(job.getRequirementMapping());
        Map<String, List<String>> mapping = new java.util.LinkedHashMap<>(dataParser.parseMappingFromJson(job.getRequirementMapping()));

        // 미배치 요구사항 수집
        java.util.Set<String> mappedIds = new java.util.HashSet<>();
        mapping.values().forEach(mappedIds::addAll);
        List<Requirement> unmapped = requirements.stream()
                .filter(r -> !mappedIds.contains(r.id()))
                .toList();

        if (unmapped.isEmpty()) {
            log.info("Job {} - no unmapped requirements, skipping section generation", jobId);
            return;
        }

        log.info("Job {} - generating leaf nodes for {} unmapped requirements", jobId, unmapped.size());
        // outline은 mutable list로 전달 — generateSectionsForUnmapped가 트리에 직접 leaf를 삽입함
        List<OutlineNode> updatedOutline = new ArrayList<>(outline);
        RequirementMapper.UnmappedSectionsResult result = requirementMapper.generateSectionsForUnmapped(updatedOutline, unmapped);

        if (result.newMapping().isEmpty()) {
            log.warn("Job {} - AI returned no mappings for unmapped requirements", jobId);
            return;
        }

        // mapping에 새 매핑 추가
        for (var entry : result.newMapping().entrySet()) {
            mapping.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
        }

        // 저장
        job.setOutline(dataParser.toJson(updatedOutline));
        var mappingResult = new java.util.LinkedHashMap<String, Object>();
        mappingResult.put(FIELD_REQUIREMENTS, requirements);
        mappingResult.put(FIELD_MAPPING, mapping);
        job.setRequirementMapping(dataParser.toJson(mappingResult));
        jobRepository.save(job);

        log.info("Job {} - added {} new mapping keys for {} unmapped requirements",
                jobId, result.newMapping().size(), unmapped.size());
    }

    private List<String> loadCustomerChunks(List<UUID> customerDocIds) {
        List<String> customerChunks = new ArrayList<>();
        for (UUID docId : customerDocIds) {
            List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(docId);
            for (DocumentChunk chunk : chunks) {
                customerChunks.add(chunk.getContent());
            }
        }
        return customerChunks;
    }
}
