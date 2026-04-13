package kr.co.mz.ragservice.document.pipeline;

import kr.co.mz.ragservice.document.Document;
import kr.co.mz.ragservice.document.DocumentChunkRepository;
import kr.co.mz.ragservice.document.DocumentRepository;
import kr.co.mz.ragservice.document.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final IngestionPipeline pipeline;
    private final TransactionTemplate tx;

    public ReindexService(DocumentRepository documentRepository,
                          DocumentChunkRepository chunkRepository,
                          IngestionPipeline pipeline,
                          PlatformTransactionManager txManager) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.pipeline = pipeline;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 단일 문서 재인덱싱: 기존 청크 삭제 후 현재 설정으로 재처리.
     * 문서 원본 바이트가 필요하므로 fileBytes를 받는다.
     * 원본이 없는 경우 재인덱싱 불가 — 현재는 업로드 시 DB에 파일을 저장하지 않으므로
     * 파일 재업로드 없이 재인덱싱하려면 기존 parent 청크 텍스트를 합쳐서 사용한다.
     */
    @Async("ingestionExecutor")
    public void reindexDocument(UUID documentId) {
        try {
            // 기존 청크 텍스트를 복원 (parent 청크만 = parentChunkId가 null)
            String fullText = tx.execute(status -> {
                Document doc = documentRepository.findById(documentId)
                        .orElseThrow(() -> new IllegalArgumentException("문서를 찾을 수 없습니다."));

                // parent 청크 텍스트를 순서대로 합침
                List<String> parentTexts = chunkRepository
                        .findByDocumentIdOrderByChunkIndex(documentId)
                        .stream()
                        .filter(c -> c.getParentChunkId() == null)
                        .map(c -> c.getContent())
                        .toList();

                if (parentTexts.isEmpty()) {
                    throw new IllegalArgumentException("재인덱싱할 청크가 없습니다.");
                }

                // 기존 청크 전부 삭제
                chunkRepository.deleteByDocumentId(documentId);

                // 상태 초기화
                doc.markProcessing();
                documentRepository.save(doc);

                return String.join("\n\n", parentTexts);
            });

            // 복원된 텍스트로 재처리 (contentType은 text/plain으로 — 이미 파싱된 텍스트)
            if (fullText == null) {
                throw new IllegalStateException("재인덱싱할 텍스트 복원에 실패했습니다.");
            }
            pipeline.doProcess(documentId, "text/plain", fullText.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        } catch (Exception e) {
            log.error("Reindex failed for document {}", documentId, e);
            try {
                tx.executeWithoutResult(status -> {
                    Document doc = documentRepository.findById(documentId).orElseThrow();
                    doc.markFailed("재인덱싱 실패: " + e.getMessage());
                    documentRepository.save(doc);
                });
            } catch (Exception ex) {
                log.error("Failed to update status after reindex failure", ex);
            }
        }
    }

    /**
     * COMPLETED 상태의 모든 문서를 재인덱싱한다.
     */
    @Async("ingestionExecutor")
    public void reindexAll() {
        List<Document> docs = documentRepository.findByStatus(DocumentStatus.COMPLETED);
        log.info("Reindexing {} documents", docs.size());
        for (Document doc : docs) {
            reindexDocument(doc.getId());
        }
    }
}
