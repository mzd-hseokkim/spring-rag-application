package kr.co.mz.ragservice.document.parser;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class MarkdownDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String contentType) {
        return "text/markdown".equals(contentType)
                || "application/octet-stream".equals(contentType);
    }

    @Override
    public String parse(byte[] fileBytes) {
        return new String(fileBytes, StandardCharsets.UTF_8);
    }
}
