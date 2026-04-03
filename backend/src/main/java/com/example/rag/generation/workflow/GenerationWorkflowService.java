package com.example.rag.generation.workflow;

import com.example.rag.generation.GenerationJob;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 퍼사드 — 각 워크플로 단계를 전담 서비스에 위임한다.
 */
@Service
public class GenerationWorkflowService {

    private final SimpleGenerationService simpleGenerationService;
    private final WizardAnalysisService wizardAnalysisService;
    private final WizardSectionService wizardSectionService;
    private final WizardRenderService wizardRenderService;

    public GenerationWorkflowService(SimpleGenerationService simpleGenerationService,
                                     WizardAnalysisService wizardAnalysisService,
                                     WizardSectionService wizardSectionService,
                                     WizardRenderService wizardRenderService) {
        this.simpleGenerationService = simpleGenerationService;
        this.wizardAnalysisService = wizardAnalysisService;
        this.wizardSectionService = wizardSectionService;
        this.wizardRenderService = wizardRenderService;
    }

    // ── 레거시 심플 워크플로 ──

    public void execute(GenerationJob detachedJob, List<UUID> documentIds) {
        simpleGenerationService.execute(detachedJob, documentIds);
    }

    // ── 위자드 Step 2: 목차 추출 ──

    public void extractOutline(UUID jobId, List<UUID> customerDocIds) {
        wizardAnalysisService.extractOutline(jobId, customerDocIds);
    }

    // ── 위자드 Step 3: 요구사항 매핑 ──

    public void mapRequirements(UUID jobId, List<UUID> customerDocIds) {
        wizardAnalysisService.mapRequirements(jobId, customerDocIds);
    }

    // ── 미배치 요구사항 처리 ──

    public void generateSectionsForUnmapped(UUID jobId) {
        wizardAnalysisService.generateSectionsForUnmapped(jobId);
    }

    // ── 위자드 Step 4: 섹션 생성 ──

    public void generateWizardSections(UUID jobId, List<UUID> refDocIds, boolean includeWebSearch) {
        wizardSectionService.generateWizardSections(jobId, refDocIds, includeWebSearch);
    }

    public void generateWizardSections(UUID jobId, List<UUID> refDocIds, boolean includeWebSearch, List<String> filterKeys, boolean forceRegenerate) {
        wizardSectionService.generateWizardSections(jobId, refDocIds, includeWebSearch, filterKeys, forceRegenerate);
    }

    // ── 위자드 Step 4: 단일 섹션 재생성 ──

    public void regenerateSection(UUID jobId, String sectionKey, List<UUID> refDocIds, boolean includeWebSearch, String userInstruction) {
        wizardSectionService.regenerateSection(jobId, sectionKey, refDocIds, includeWebSearch, userInstruction);
    }

    // ── 위자드 Step 5: 렌더링 ──

    public void renderWizardDocument(UUID jobId) {
        wizardRenderService.renderWizardDocument(jobId);
    }
}
