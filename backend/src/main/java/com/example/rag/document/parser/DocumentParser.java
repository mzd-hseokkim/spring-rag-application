package com.example.rag.document.parser;

public interface DocumentParser {

    boolean supports(String contentType);

    String parse(byte[] fileBytes);
}
