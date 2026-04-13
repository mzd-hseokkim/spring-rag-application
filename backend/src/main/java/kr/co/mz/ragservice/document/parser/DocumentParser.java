package kr.co.mz.ragservice.document.parser;

public interface DocumentParser {

    boolean supports(String contentType);

    String parse(byte[] fileBytes);
}
