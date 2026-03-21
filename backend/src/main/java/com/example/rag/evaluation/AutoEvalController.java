package com.example.rag.evaluation;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/eval")
@PreAuthorize("hasRole('ADMIN')")
public class AutoEvalController {

    private final AutoEvalService evalService;

    public AutoEvalController(AutoEvalService evalService) {
        this.evalService = evalService;
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EvalRunEntity generate(@RequestBody GenerateRequest request) {
        EvalRunEntity run = evalService.createRun(request.name());
        evalService.generateQuestions(run.getId(), request.documentIds(), request.questionsPerChunk());
        return run;
    }

    @PostMapping("/runs/{id}/execute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EvalRunEntity execute(@PathVariable UUID id) {
        EvalRunEntity run = evalService.getRun(id);
        evalService.executeRun(id);
        return run;
    }

    @GetMapping("/runs")
    public List<EvalRunEntity> listRuns() {
        return evalService.listRuns();
    }

    @GetMapping("/runs/{id}")
    public EvalRunEntity getRun(@PathVariable UUID id) {
        return evalService.getRun(id);
    }

    @GetMapping("/runs/{id}/questions")
    public List<QuestionSummary> getQuestions(@PathVariable UUID id) {
        return evalService.getQuestions(id).stream()
                .map(q -> new QuestionSummary(q.getId(), q.getQuestion(), q.getQuestionType(),
                        q.getFaithfulness(), q.getRelevance(), q.getCorrectness(),
                        q.getJudgeComment(), q.getStatus()))
                .toList();
    }

    @GetMapping("/questions/{qid}")
    public EvalQuestionEntity getQuestionDetail(@PathVariable UUID qid) {
        return evalService.getQuestionDetail(qid);
    }

    record QuestionSummary(UUID id, String question, String questionType,
                            Double faithfulness, Double relevance, Double correctness,
                            String judgeComment, String status) {}

    @DeleteMapping("/runs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRun(@PathVariable UUID id) {
        evalService.deleteRun(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleError(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }

    record GenerateRequest(String name, List<UUID> documentIds, int questionsPerChunk) {}
}
