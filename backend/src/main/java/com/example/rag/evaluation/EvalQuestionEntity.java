package com.example.rag.evaluation;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "eval_question")
public class EvalQuestionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "eval_run_id", nullable = false)
    private UUID evalRunId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "expected_answer", nullable = false, columnDefinition = "TEXT")
    private String expectedAnswer;

    @Column(name = "question_type", nullable = false, length = 20)
    private String questionType;

    @Column(name = "actual_response", columnDefinition = "TEXT")
    private String actualResponse;

    @Column(name = "retrieved_context", columnDefinition = "TEXT")
    private String retrievedContext;

    private Double faithfulness;
    private Double relevance;
    private Double correctness;

    @Column(name = "judge_comment", columnDefinition = "TEXT")
    private String judgeComment;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected EvalQuestionEntity() {}

    public EvalQuestionEntity(UUID evalRunId, UUID documentId, String question,
                               String expectedAnswer, String questionType) {
        this.evalRunId = evalRunId;
        this.documentId = documentId;
        this.question = question;
        this.expectedAnswer = expectedAnswer;
        this.questionType = questionType;
    }

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }

    public UUID getId() { return id; }
    public UUID getEvalRunId() { return evalRunId; }
    public UUID getDocumentId() { return documentId; }
    public String getQuestion() { return question; }
    public String getExpectedAnswer() { return expectedAnswer; }
    public String getQuestionType() { return questionType; }
    public String getActualResponse() { return actualResponse; }
    public String getRetrievedContext() { return retrievedContext; }
    public Double getFaithfulness() { return faithfulness; }
    public Double getRelevance() { return relevance; }
    public Double getCorrectness() { return correctness; }
    public String getJudgeComment() { return judgeComment; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setActualResponse(String actualResponse) { this.actualResponse = actualResponse; }
    public void setRetrievedContext(String retrievedContext) { this.retrievedContext = retrievedContext; }
    public void setFaithfulness(Double faithfulness) { this.faithfulness = faithfulness; }
    public void setRelevance(Double relevance) { this.relevance = relevance; }
    public void setCorrectness(Double correctness) { this.correctness = correctness; }
    public void setJudgeComment(String judgeComment) { this.judgeComment = judgeComment; }
    public void setStatus(String status) { this.status = status; }
}
