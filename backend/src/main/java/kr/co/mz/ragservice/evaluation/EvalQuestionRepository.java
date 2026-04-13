package kr.co.mz.ragservice.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface EvalQuestionRepository extends JpaRepository<EvalQuestionEntity, UUID> {

    List<EvalQuestionEntity> findByEvalRunIdOrderByCreatedAt(UUID evalRunId);

    long countByEvalRunId(UUID evalRunId);

    @Query("SELECT AVG(q.faithfulness) FROM EvalQuestionEntity q WHERE q.evalRunId = :runId AND q.faithfulness IS NOT NULL")
    Double avgFaithfulness(UUID runId);

    @Query("SELECT AVG(q.relevance) FROM EvalQuestionEntity q WHERE q.evalRunId = :runId AND q.relevance IS NOT NULL")
    Double avgRelevance(UUID runId);

    @Query("SELECT AVG(q.correctness) FROM EvalQuestionEntity q WHERE q.evalRunId = :runId AND q.correctness IS NOT NULL")
    Double avgCorrectness(UUID runId);
}
