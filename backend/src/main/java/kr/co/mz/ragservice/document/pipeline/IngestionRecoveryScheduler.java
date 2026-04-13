package kr.co.mz.ragservice.document.pipeline;

import kr.co.mz.ragservice.document.Document;
import kr.co.mz.ragservice.document.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Profile("worker | default")
public class IngestionRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionRecoveryScheduler.class);
    private static final int MAX_RETRY_COUNT = 3;

    private final DocumentRepository documentRepository;
    private final IngestionEventPublisher eventPublisher;

    public IngestionRecoveryScheduler(DocumentRepository documentRepository,
                                      IngestionEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    @Transactional
    public void recoverStaleDocuments() {
        List<Document> stale = documentRepository.findStaleProcessingDocuments(LocalDateTime.now());
        if (stale.isEmpty()) return;

        for (Document doc : stale) {
            if (doc.getRetryCount() >= MAX_RETRY_COUNT) {
                doc.markFailed("최대 재시도 횟수(" + MAX_RETRY_COUNT + "회) 초과");
                documentRepository.save(doc);
                eventPublisher.publish(doc.getId(), "FAILED", "최대 재시도 횟수 초과로 처리 실패");
                log.warn("Document {} exceeded max retries ({}) — marked FAILED (leased by: {})",
                        doc.getId(), MAX_RETRY_COUNT, doc.getLeasedBy());
            } else {
                doc.markPendingForRetry();
                documentRepository.save(doc);
                log.info("Document {} recovered from stale PROCESSING → PENDING (retry #{}, was leased by: {})",
                        doc.getId(), doc.getRetryCount(), doc.getLeasedBy());
            }
        }
    }
}
