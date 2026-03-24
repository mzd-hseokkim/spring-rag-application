package com.example.rag.questionnaire.renderer;

import com.example.rag.questionnaire.workflow.PersonaQna;
import com.example.rag.questionnaire.workflow.QuestionAnswer;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class QuestionnaireHtmlRenderer {

    private final TemplateEngine templateEngine;
    private final Path storageBase;

    public QuestionnaireHtmlRenderer(TemplateEngine templateEngine,
                                     @Value("${app.upload.generation-dir:./uploads/generations}") String storagePath) {
        this.templateEngine = templateEngine;
        this.storageBase = Path.of(storagePath);
    }

    /**
     * Excel(.xlsx)과 HTML 미리보기를 모두 생성한다.
     * 반환값은 Excel 파일 경로.
     */
    public String render(List<PersonaQna> allQna, UUID userId, UUID jobId) {
        Path outputDir = storageBase.resolve("questionnaires").resolve(userId.toString());
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory", e);
        }

        // Excel 생성
        String excelPath = renderExcel(allQna, outputDir, jobId);

        // HTML 미리보기 생성
        renderHtml(allQna, outputDir, jobId);

        return excelPath;
    }

    /**
     * HTML 미리보기 파일 경로 반환 (preview 엔드포인트용)
     */
    public String getPreviewPath(String excelPath) {
        return excelPath.replace(".xlsx", ".html");
    }

    private String renderExcel(List<PersonaQna> allQna, Path outputDir, UUID jobId) {
        Path outputFile = outputDir.resolve(jobId + ".xlsx");

        try (Workbook workbook = new XSSFWorkbook()) {
            // 스타일 정의
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle personaHeaderStyle = createPersonaHeaderStyle(workbook);
            CellStyle wrapStyle = createWrapStyle(workbook);
            CellStyle diffHighStyle = createDifficultyStyle(workbook, IndexedColors.CORAL);
            CellStyle diffMidStyle = createDifficultyStyle(workbook, IndexedColors.LIGHT_YELLOW);
            CellStyle diffLowStyle = createDifficultyStyle(workbook, IndexedColors.LIGHT_GREEN);

            Sheet sheet = workbook.createSheet("예상 질의서");

            // 컬럼 폭 설정
            sheet.setColumnWidth(0, 3 * 256);   // #
            sheet.setColumnWidth(1, 50 * 256);  // 질문
            sheet.setColumnWidth(2, 60 * 256);  // 모범답변
            sheet.setColumnWidth(3, 8 * 256);   // 난이도
            sheet.setColumnWidth(4, 14 * 256);  // 카테고리
            sheet.setColumnWidth(5, 30 * 256);  // 출처

            int rowIdx = 0;

            for (PersonaQna persona : allQna) {
                // 페르소나 헤더 행
                Row personaRow = sheet.createRow(rowIdx++);
                Cell personaCell = personaRow.createCell(0);
                personaCell.setCellValue(persona.personaName() + " — " + persona.personaRole());
                personaCell.setCellStyle(personaHeaderStyle);
                sheet.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, 5));

                // 컬럼 헤더
                Row headerRow = sheet.createRow(rowIdx++);
                String[] headers = {"#", "질문", "모범답변", "난이도", "카테고리", "출처"};
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                }

                // 질문 데이터
                List<QuestionAnswer> questions = persona.questions();
                for (int i = 0; i < questions.size(); i++) {
                    QuestionAnswer qa = questions.get(i);
                    Row dataRow = sheet.createRow(rowIdx++);

                    dataRow.createCell(0).setCellValue(i + 1);

                    Cell qCell = dataRow.createCell(1);
                    qCell.setCellValue(qa.question());
                    qCell.setCellStyle(wrapStyle);

                    Cell aCell = dataRow.createCell(2);
                    aCell.setCellValue(qa.answer());
                    aCell.setCellStyle(wrapStyle);

                    Cell dCell = dataRow.createCell(3);
                    dCell.setCellValue(qa.difficulty());
                    switch (qa.difficulty()) {
                        case "상" -> dCell.setCellStyle(diffHighStyle);
                        case "하" -> dCell.setCellStyle(diffLowStyle);
                        default -> dCell.setCellStyle(diffMidStyle);
                    }

                    dataRow.createCell(4).setCellValue(qa.category());

                    String sources = qa.sources() != null ? String.join(", ", qa.sources()) : "";
                    Cell sCell = dataRow.createCell(5);
                    sCell.setCellValue(sources);
                    sCell.setCellStyle(wrapStyle);
                }

                // 페르소나 구분 빈 행
                rowIdx++;
            }

            try (FileOutputStream fos = new FileOutputStream(outputFile.toFile())) {
                workbook.write(fos);
            }
            return outputFile.toString();

        } catch (IOException e) {
            throw new RuntimeException("Failed to write questionnaire Excel file", e);
        }
    }

    private void renderHtml(List<PersonaQna> allQna, Path outputDir, UUID jobId) {
        int totalQuestions = allQna.stream()
                .mapToInt(q -> q.questions().size())
                .sum();

        Context ctx = new Context();
        ctx.setVariable("allQna", allQna);
        ctx.setVariable("totalQuestions", totalQuestions);
        ctx.setVariable("totalPersonas", allQna.size());
        ctx.setVariable("generatedAt", LocalDateTime.now());

        String html = templateEngine.process("questionnaire/qna-report", ctx);

        Path outputFile = outputDir.resolve(jobId + ".html");
        try {
            Files.writeString(outputFile, html);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write questionnaire HTML file", e);
        }
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private CellStyle createPersonaHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        font.setColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle createWrapStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }

    private CellStyle createDifficultyStyle(Workbook workbook, IndexedColors color) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(color.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }
}
