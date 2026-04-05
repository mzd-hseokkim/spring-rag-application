package com.example.rag.generation.workflow;

import com.example.rag.dashboard.GenerationTraceEntity;
import com.example.rag.dashboard.GenerationTraceService;
import com.example.rag.model.TokenRecordingContext;
import com.example.rag.generation.GenerationJob;
import com.example.rag.generation.GenerationJobRepository;
import com.example.rag.generation.GenerationStatus;
import com.example.rag.generation.dto.GenerationProgressEvent;
import com.example.rag.generation.renderer.DocumentRendererService;
import com.example.rag.search.ChunkSearchResult;
import com.example.rag.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SimpleGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SimpleGenerationService.class);
    private static final int MAX_RETRIES = 2;
    private static final String JOB_NOT_FOUND = "Job not found: ";
    private static final String TRACE_PURPOSE = "GENERATION";

    private final ContentGeneratorService contentGenerator;
    private final DocumentRendererService rendererService;
    private final SearchService searchService;
    private final GenerationJobRepository jobRepository;
    private final GenerationDataParser dataParser;
    private final GenerationTraceService traceService;
    private final WorkflowEventEmitter eventEmitter;

    public SimpleGenerationService(ContentGeneratorService contentGenerator,
                                   DocumentRendererService rendererService,
                                   SearchService searchService,
                                   GenerationJobRepository jobRepository,
                                   GenerationDataParser dataParser,
                                   GenerationTraceService traceService,
                                   WorkflowEventEmitter eventEmitter) {
        this.contentGenerator = contentGenerator;
        this.rendererService = rendererService;
        this.searchService = searchService;
        this.jobRepository = jobRepository;
        this.dataParser = dataParser;
        this.traceService = traceService;
        this.eventEmitter = eventEmitter;
    }

    @Async("ingestionExecutor")
    public void execute(GenerationJob detachedJob, List<UUID> documentIds) {
        // @Async는 별도 스레드에서 실행되므로, 호출측 트랜잭션 커밋 후 DB에서 다시 조회 (template, user eager fetch)
        GenerationJob job = jobRepository.findByIdWithTemplate(detachedJob.getId())
                .orElseThrow(() -> new IllegalStateException(JOB_NOT_FOUND + detachedJob.getId()));
        TokenRecordingContext.setUserId(job.getUser().getId());
        GenerationTraceEntity currentTrace = null;
        try {
            // Phase 1: PLAN
            currentTrace = traceService.start(job.getId(), TRACE_PURPOSE, "OUTLINE");
            eventEmitter.updateStatus(job, GenerationStatus.PLANNING);
            eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.PLANNING, "문서 구조를 설계하고 있습니다..."));

            List<String> ragContext = searchRelevantDocs(job, documentIds);
            String systemPrompt = job.getTemplate().getSystemPrompt();
            if (systemPrompt == null || systemPrompt.isBlank()) {
                systemPrompt = "당신은 전문 문서 작성자입니다. 명확하고 구조적인 문장을 사용하세요.";
            }

            DocumentOutline outline = contentGenerator.generateOutline(
                    job.getUserInput(), systemPrompt, ragContext);

            job.setOutline(dataParser.toJson(outline));
            job.setTotalSections(outline.sections().size());
            jobRepository.save(job);
            traceService.complete(currentTrace);
            log.info("Generation job {} - outline created with {} sections", job.getId(), outline.sections().size());

            // Phase 2: GENERATE
            currentTrace = traceService.start(job.getId(), TRACE_PURPOSE, "SECTION_GENERATE");
            eventEmitter.updateStatus(job, GenerationStatus.GENERATING);
            List<SectionContent> sections = new ArrayList<>();

            for (int i = 0; i < outline.sections().size(); i++) {
                SectionPlan plan = outline.sections().get(i);
                job.setCurrentSection(i + 1);
                jobRepository.save(job);

                eventEmitter.emitEvent(job, GenerationProgressEvent.progress(
                        i + 1, outline.sections().size(), plan.heading(), plan.key()));

                List<String> sectionContext = searchForSection(plan, documentIds);
                SectionContent content = generateWithRetry(plan, systemPrompt, sectionContext, sections);
                sections.add(content);

                if (log.isInfoEnabled()) {
                    log.info("Generation job {} - section {}/{} complete: {}",
                            job.getId(), i + 1, outline.sections().size(), plan.heading());
                }
            }

            job.setGeneratedSections(dataParser.toJson(sections));
            jobRepository.save(job);
            traceService.complete(currentTrace);

            // Phase 3: REVIEW — 향후 구현 (Plan 05)

            // Phase 4: RENDER
            currentTrace = traceService.start(job.getId(), TRACE_PURPOSE, "RENDER");
            eventEmitter.updateStatus(job, GenerationStatus.RENDERING);
            eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.RENDERING, "최종 문서를 생성하고 있습니다..."));

            String outputPath = rendererService.render(job.getTemplate(), outline, sections, job.getUser().getId(), job.getId());
            job.setOutputFilePath(outputPath);

            eventEmitter.updateStatus(job, GenerationStatus.COMPLETE);
            traceService.complete(currentTrace);
            eventEmitter.emitEvent(job, GenerationProgressEvent.complete("/api/generations/" + job.getId() + "/download"));
            log.info("Generation job {} - complete, output: {}", job.getId(), outputPath);

        } catch (Exception e) {
            log.error("Generation job {} failed", job.getId(), e);
            if (currentTrace != null) traceService.fail(currentTrace, e.getMessage());
            job.setErrorMessage(e.getMessage());
            eventEmitter.updateStatus(job, GenerationStatus.FAILED);
            eventEmitter.emitEvent(job, GenerationProgressEvent.error(e.getMessage()));
        } finally {
            TokenRecordingContext.clear();
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
}
