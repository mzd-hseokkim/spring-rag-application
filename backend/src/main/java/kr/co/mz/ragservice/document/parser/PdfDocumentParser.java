package kr.co.mz.ragservice.document.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.Table;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    @Override
    public boolean supports(String contentType) {
        return "application/pdf".equals(contentType);
    }

    @Override
    public String parse(byte[] fileBytes) {
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            // 1. 기본 텍스트 추출
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            // 2. 표 추출 시도
            List<String> tables = extractTables(document);

            if (tables.isEmpty()) {
                return text;
            }

            // 텍스트 끝에 추출된 표 추가 (마크다운 형식)
            StringBuilder sb = new StringBuilder(text);
            for (int i = 0; i < tables.size(); i++) {
                sb.append("\n\n<!-- TABLE ").append(i + 1).append(" -->\n");
                sb.append(tables.get(i));
                sb.append("\n<!-- /TABLE -->\n");
            }
            return sb.toString();

        } catch (IOException e) {
            throw new kr.co.mz.ragservice.common.RagException("Failed to parse PDF", e);
        }
    }

    private List<String> extractTables(PDDocument document) {
        List<String> markdownTables = new ArrayList<>();
        try (ObjectExtractor extractor = new ObjectExtractor(document)) {
            SpreadsheetExtractionAlgorithm algorithm = new SpreadsheetExtractionAlgorithm();

            for (int pageNum = 1; pageNum <= document.getNumberOfPages(); pageNum++) {
                Page page = extractor.extract(pageNum);
                List<Table> tables = algorithm.extract(page);

                for (Table table : tables) {
                    String md = tableToMarkdown(table);
                    if (!md.isBlank()) {
                        markdownTables.add(md);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract tables from PDF, continuing with text only: {}", e.getMessage());
        }
        return markdownTables;
    }

    private String tableToMarkdown(Table table) {
        if (table.getRowCount() < 2 || table.getColCount() < 2) {
            return "";
        }

        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r < table.getRowCount(); r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < table.getColCount(); c++) {
                String cell = table.getCell(r, c).getText().trim().replace("|", "\\|");
                row.add(cell);
            }
            rows.add(row);
        }

        // 빈 행만 있으면 스킵
        boolean hasContent = rows.stream().anyMatch(row -> row.stream().anyMatch(c -> !c.isBlank()));
        if (!hasContent) return "";

        StringBuilder sb = new StringBuilder();
        // 헤더
        sb.append("| ").append(String.join(" | ", rows.get(0))).append(" |\n");
        sb.append("| ").append(rows.get(0).stream().map(c -> "---").collect(Collectors.joining(" | "))).append(" |\n");
        // 데이터 행
        for (int i = 1; i < rows.size(); i++) {
            sb.append("| ").append(String.join(" | ", rows.get(i))).append(" |\n");
        }
        return sb.toString();
    }
}
