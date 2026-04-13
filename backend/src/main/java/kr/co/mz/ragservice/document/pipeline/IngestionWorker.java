package kr.co.mz.ragservice.document.pipeline;

import kr.co.mz.ragservice.document.Document;
import kr.co.mz.ragservice.document.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
@Profile("worker | default")
public class IngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);
    private static final int BATCH_SIZE = 4;
    private static final int LEASE_SECONDS = 300; // 5분 리스

    private final DocumentRepository documentRepository;
    private final IngestionPipeline ingestionPipeline;
    private final TaskExecutor ingestionExecutor;
    private final TransactionTemplate tx;
    private final String workerId;

    public IngestionWorker(DocumentRepository documentRepository,
                           IngestionPipeline ingestionPipeline,
                           @Qualifier("ingestionExecutor") TaskExecutor ingestionExecutor,
                           PlatformTransactionManager txManager) {
        this.documentRepository = documentRepository;
        this.ingestionPipeline = ingestionPipeline;
        this.ingestionExecutor = ingestionExecutor;
        this.tx = new TransactionTemplate(txManager);
        this.workerId = resolveWorkerId();
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 3000)
    public void poll() {
        List<UUID> claimed = tx.execute(status -> {
            List<UUID> pending = documentRepository.findPendingForClaim(BATCH_SIZE);
            if (pending.isEmpty()) return pending;

            LocalDateTime leaseExpiry = LocalDateTime.now().plusSeconds(LEASE_SECONDS);
            for (UUID docId : pending) {
                Document doc = documentRepository.findById(docId).orElse(null);
                if (doc != null) {
                    doc.markProcessing(workerId, leaseExpiry);
                    documentRepository.save(doc);
                }
            }
            return pending;
        });

        if (claimed == null || claimed.isEmpty()) return;

        log.info("Worker [{}] claimed {} documents: {}", workerId, claimed.size(), claimed);

        for (UUID docId : claimed) {
            ingestionExecutor.execute(() -> processDocument(docId));
        }
    }

    private void processDocument(UUID documentId) {
        try {
            // storedPath에서 파일 바이트 읽기
            Document doc = documentRepository.findById(documentId).orElse(null);
            if (doc == null) return;

            String storedPath = doc.getStoredPath();
            String contentType = doc.getContentType();

            byte[] fileBytes;
            if (storedPath != null) {
                Path path = Paths.get(storedPath);
                if (Files.exists(path)) {
                    fileBytes = Files.readAllBytes(path);
                } else {
                    throw new IllegalStateException("저장된 파일을 찾을 수 없습니다: " + storedPath);
                }
            } else {
                throw new IllegalStateException("파일 저장 경로가 없습니다");
            }

            ingestionPipeline.doProcess(documentId, contentType, fileBytes);

        } catch (Exception e) {
            log.error("Worker [{}] failed to process document {}", workerId, documentId, e);
            try {
                tx.executeWithoutResult(status -> {
                    Document doc = documentRepository.findById(documentId).orElseThrow();
                    doc.markFailed(e.getMessage());
                    documentRepository.save(doc);
                });
            } catch (Exception ex) {
                log.error("Failed to mark document {} as FAILED", documentId, ex);
            }
        }
    }

    private static String resolveWorkerId() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            return host + ":" + pid;
        } catch (Exception e) {
            return "worker-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}
