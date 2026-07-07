package com.rag.campus.support.impl;

import com.rag.campus.support.DocumentConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Word 文档文本提取器（基于 Apache POI）
 * <p>
 * .docx → 遍历 body 元素，表格格式化为 Markdown 表格，避免表格结构丢失
 * .doc  → HWPFDocument (二进制) — 基础文本提取，表格支持有限
 */
@Slf4j
@Component
public class DocxConverter implements DocumentConverter {

    private static final Set<String> EXTENSIONS = Set.of(".docx", ".doc");

    @Override
    public String convert(byte[] fileBytes, String filename) throws IOException {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".docx")) {
            return extractFromDocx(fileBytes, filename);
        } else {
            return extractFromDoc(fileBytes, filename);
        }
    }

    // ==================== .docx 提取 ====================

    /**
     * .docx 文本提取 — 遍历 body 元素，表格 → Markdown 表格
     * <p>
     * 为什么不直接用 XWPFWordExtractor？
     * 它把表格内容按单元格顺序平铺，丢失行列结构，下游滑动窗口分块
     * 可能把一行数据拦腰截断，导致 RAG 检索时 cell 值脱离表头上下文。
     * <p>
     * 本方法逐元素遍历 XWPFDocument.bodyElements：
     * - XWPFParagraph → 输出段落文本
     * - XWPFTable    → 输出 Markdown 表格（管道符 + 分隔线）
     */
    private String extractFromDocx(byte[] fileBytes, String filename) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(fileBytes);
             XWPFDocument doc = new XWPFDocument(bis)) {

            StringBuilder sb = new StringBuilder();
            List<IBodyElement> elements = doc.getBodyElements();

            for (IBodyElement element : elements) {
                switch (element.getElementType()) {
                    case PARAGRAPH -> {
                        XWPFParagraph para = (XWPFParagraph) element;
                        String text = para.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append(text).append("\n");
                        }
                    }
                    case TABLE -> {
                        XWPFTable table = (XWPFTable) element;
                        sb.append(formatTableAsMarkdown(table)).append("\n");
                    }
                    // CONTENTCONTROL, UNKNOWN 等类型忽略
                    default -> {}
                }
            }

            String text = sb.toString().trim();
            if (text.isBlank()) {
                throw new IOException("Word文档无可提取的文字内容，可能为空文档或仅含图片");
            }
            log.info("DOCX文本提取成功: {}, 字符数={}", filename, text.length());
            return text;
        }
    }

    /**
     * 将 XWPFTable 格式化为 Markdown 表格
     * <p>
     * 输出格式:
     * | Header1 | Header2 |
     * |---------|---------|
     * | cell1   | cell2   |
     * <p>
     * 合并单元格处理：POI 读取合并格会在后续行返回空串，
     * 保留空串不做填充，避免虚构数据污染检索结果。
     */
    private String formatTableAsMarkdown(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return "";
        }

        // 计算列数（取所有行中最大列数，兼容不规则表格）
        int maxCols = 0;
        for (XWPFTableRow row : rows) {
            int cols = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                // 合并单元格会跨多列，这里简化处理：每个 cell 算一列
                cols++;
            }
            maxCols = Math.max(maxCols, cols);
        }

        if (maxCols == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
            XWPFTableRow row = rows.get(rowIdx);
            sb.append("| ");

            int cellCount = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText().replace("\n", " ").replace("|", "\\|").trim();
                sb.append(cellText).append(" | ");
                cellCount++;
            }
            // 补齐不足 maxCols 的列（合并单元格导致）
            while (cellCount < maxCols) {
                sb.append(" | ");
                cellCount++;
            }
            sb.append("\n");

            // 第一行后插入分隔线
            if (rowIdx == 0) {
                sb.append("|");
                for (int c = 0; c < maxCols; c++) {
                    sb.append("---|");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    // ==================== .doc 提取 ====================

    /**
     * .doc 文本提取（HWPF，旧二进制格式）
     * <p>
     * HWPF 对表格的处理能力有限，表格内容按段落顺序输出。
     * 建议用户优先使用 .docx 格式以获得更好的表格结构保留。
     */
    private String extractFromDoc(byte[] fileBytes, String filename) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(fileBytes);
             HWPFDocument doc = new HWPFDocument(bis);
             WordExtractor extractor = new WordExtractor(doc)) {
            String text = extractor.getText();
            if (text.isBlank()) {
                throw new IOException("Word文档无可提取的文字内容，可能为空文档或仅含图片");
            }
            log.info("DOC文本提取成功: {}, 字符数={} (表格结构可能丢失，建议使用.docx格式)", filename, text.length());
            return text;
        }
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }
}
