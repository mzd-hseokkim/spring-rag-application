package com.example.rag.document.parser;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class TextDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String contentType) {
        return "text/plain".equals(contentType);
    }

    @Override
    public String parse(byte[] fileBytes) {
        return new String(fileBytes, StandardCharsets.UTF_8);
    }
}
