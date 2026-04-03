package com.example.rag.generation.workflow;

import com.example.rag.dashboard.GenerationTraceEntity;
import com.example.rag.dashboard.GenerationTraceService;
import com.example.rag.model.TokenRecordingContext;
import com.example.rag.generation.GenerationJob;
import com.example.rag.generation.GenerationJobRepository;
import com.example.rag.generation.GenerationStatus;
import com.example.rag.generation.dto.GenerationProgressEvent;
import com.example.rag.generation.dto.OutlineNode;
import com.example.rag.generation.renderer.DocumentRendererService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class WizardRenderService {

    private static final Logger log = LoggerFactory.getLogger(WizardRenderService.class);
    private static final String JOB_NOT_FOUND = "Job not found: ";
    private static final String STEP_STATUS_PROCESSING = "PROCESSING";
    private static final String STEP_STATUS_COMPLETE = "COMPLETE";
    private static final String STEP_STATUS_FAILED = "FAILED";

    private final OutlineExtractor outlineExtractor;
    private final DocumentRendererService rendererService;
    private final GenerationJobRepository jobRepository;
    private final GenerationDataParser dataParser;
    private final GenerationTraceService traceService;
    private final WorkflowEventEmitter eventEmitter;

    public WizardRenderService(OutlineExtractor outlineExtractor,
                               DocumentRendererService rendererService,
                               GenerationJobRepository jobRepository,
                               GenerationDataParser dataParser,
                               GenerationTraceService traceService,
                               WorkflowEventEmitter eventEmitter) {
        this.outlineExtractor = outlineExtractor;
        this.rendererService = rendererService;
        this.jobRepository = jobRepository;
        this.dataParser = dataParser;
        this.traceService = traceService;
        this.eventEmitter = eventEmitter;
    }

    @Async("ingestionExecutor")
    public void renderWizardDocument(UUID jobId) {
        GenerationJob job = jobRepository.findByIdWithTemplate(jobId)
                .orElseThrow(() -> new IllegalStateException(JOB_NOT_FOUND + jobId));
        TokenRecordingContext.setUserId(job.getUser().getId());
        GenerationTraceEntity trace = traceService.start(jobId, "GENERATION", "RENDER");
        try {
            job.setStatus(GenerationStatus.RENDERING);
            job.setCurrentStep(5);
            job.setStepStatus(STEP_STATUS_PROCESSING);
            jobRepository.save(job);

            eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.RENDERING, "최종 문서를 렌더링하고 있습니다..."));

            // 목차에서 DocumentOutline 구성
            List<OutlineNode> outlineNodes = outlineExtractor.fromJson(job.getOutline());
            String docTitle = job.getTitle() != null && !job.getTitle().isBlank()
                    ? job.getTitle() : "제안서";
            List<SectionPlan> allPlans = new ArrayList<>();
            OutlineUtils.flattenOutlineNodes(outlineNodes, allPlans);
            DocumentOutline outline = new DocumentOutline(docTitle, "", allPlans, outlineNodes);

            // 섹션 파싱 (key 기준 정렬)
            List<SectionContent> sections = dataParser.parseSections(job.getGeneratedSections());
            if (sections.isEmpty()) {
                throw new IllegalStateException("Failed to parse generated sections");
            }
            sections = sections.stream()
                    .sorted(java.util.Comparator.comparing(SectionContent::key, OutlineUtils::compareKeys))
                    .toList();

            // HTML 렌더링
            String outputPath = rendererService.render(job.getTemplate(), outline, sections, job.getUser().getId(), job.getId());
            job.setOutputFilePath(outputPath);
            job.setCurrentStep(5);
            job.setStepStatus(STEP_STATUS_COMPLETE);
            job.setStatus(GenerationStatus.COMPLETE);
            jobRepository.save(job);

            traceService.complete(trace);
            eventEmitter.emitEvent(job, GenerationProgressEvent.complete("/api/generations/" + job.getId() + "/download"));
            log.info("Generation job {} - wizard rendering complete, output: {}", jobId, outputPath);

        } catch (Exception e) {
            log.error("Generation job {} wizard rendering failed", jobId, e);
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
}
