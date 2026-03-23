package com.example.rag.generation.workflow;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentTable(
        @JsonAlias("title") String caption,
        List<String> headers,
        List<List<String>> rows
) {}
