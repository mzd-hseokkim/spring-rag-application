package com.example.rag.document.tag;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "document_tag")
public class DocumentTag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    protected DocumentTag() {}

    public DocumentTag(String name) {
        this.name = name;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
