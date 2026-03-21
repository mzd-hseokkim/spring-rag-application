package com.example.rag.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EvalLeaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvalLeaseScheduler.class);

    private final AutoEvalService evalService;

    public EvalLeaseScheduler(AutoEvalService evalService) {
        this.evalService = evalService;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void recoverStaleRuns() {
        List<EvalRunEntity> staleRuns = evalService.findStaleRuns();
        for (EvalRunEntity run : staleRuns) {
            log.info("Recovering stale eval run: {} (status={}, leasedBy={})",
                    run.getId(), run.getStatus(), run.getLeasedBy());

            if ("GENERATING".equals(run.getStatus())) {
                // 질문 생성 중 죽은 경우: 이미 생성된 질문이 있으면 READY로 전환, 없으면 FAILED
                long questionCount = evalService.getQuestions(run.getId()).size();
                if (questionCount > 0) {
                    run.setTotalQuestions((int) questionCount);
                    run.setStatus("READY");
                    run.setLeasedUntil(null);
                    run.setLeasedBy(null);
                    log.info("Recovered GENERATING run {} → READY ({} questions)", run.getId(), questionCount);
                } else {
                    run.setStatus("FAILED");
                    run.setLeasedUntil(null);
                    run.setLeasedBy(null);
                    log.info("Recovered GENERATING run {} → FAILED (no questions)", run.getId());
                }
            } else if ("RUNNING".equals(run.getStatus())) {
                // 평가 실행 중 죽은 경우: PENDING 질문이 남아있으면 이어서 실행
                log.info("Resuming stale RUNNING eval run: {}", run.getId());
                evalService.executeRun(run.getId());
            }
        }
    }
}
