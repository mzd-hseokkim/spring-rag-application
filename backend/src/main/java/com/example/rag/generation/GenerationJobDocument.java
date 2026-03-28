package com.example.rag.generation;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "generation_job_document")
@IdClass(GenerationJobDocument.PK.class)
public class GenerationJobDocument {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "document_role", nullable = false, length = 20)
    private String documentRole;

    protected GenerationJobDocument() {}

    public GenerationJobDocument(UUID jobId, UUID documentId, String documentRole) {
        this.jobId = jobId;
        this.documentId = documentId;
        this.documentRole = documentRole;
    }

    public UUID getJobId() { return jobId; }
    public UUID getDocumentId() { return documentId; }
    public String getDocumentRole() { return documentRole; }

    public static class PK implements Serializable {
        private UUID jobId;
        private UUID documentId;

        public PK() {}
        public PK(UUID jobId, UUID documentId) { this.jobId = jobId; this.documentId = documentId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return jobId.equals(pk.jobId) && documentId.equals(pk.documentId);
        }

        @Override
        public int hashCode() { return java.util.Objects.hash(jobId, documentId); }
    }
}
