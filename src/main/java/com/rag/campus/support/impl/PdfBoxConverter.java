package com.rag.campus.support.impl;

import com.rag.campus.support.DocumentConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PDF 文本提取器（基于 Apache PDFBox）
 * <p>
 * 通过分析文本坐标检测表格结构，将表格区域输出为 Markdown 表格格式。
 * 文本提取委托给 PDFBox 原生 writeString（保留下标/基线处理），
 * 仅在 {@code processTextPosition} 中收集坐标用于表格检测。
 * <p>
 * 注意：扫描版 PDF（图片型）无法提取，需 OCR。
 */
@Slf4j
@Component
public class PdfBoxConverter implements DocumentConverter {

    private static final Set<String> EXTENSIONS = Set.of(".pdf");

    /** 同一行文本的 Y 坐标容差（磅） */
    private static final float ROW_Y_TOLERANCE = 4.0f;

    /** 列间最小间距（相对于平均字符宽度的倍数） */
    private static final float COLUMN_GAP_RATIO = 2.5f;

    /** 构成表格的最少连续行数 */
    private static final int MIN_TABLE_ROWS = 3;

    /** 同一列的 X 坐标跨行对齐容差（磅） */
    private static final float COL_ALIGN_TOLERANCE = 8.0f;

    @Override
    public String convert(byte[] fileBytes, String filename) throws IOException {
        try (PDDocument pdfDoc = PDDocument.load(fileBytes)) {

            TableAwareStripper stripper = new TableAwareStripper();
            stripper.setSortByPosition(true);

            // 触发 PDFBox 原生文本提取（writeString 未被覆盖，保留下标处理）
            // 同时 processTextPosition 收集坐标用于表格检测
            String normalText = stripper.getText(pdfDoc);

            // 用坐标数据检测表格，将表格区域替换为 Markdown
            String result = stripper.injectMarkdownTables(normalText);

            if (result.isBlank()) {
                throw new IOException("PDF文件无可提取的文字内容，可能是扫描版PDF");
            }
            log.info("PDF文本提取成功: {}, 字符数={}, 检测表格数={}",
                    filename, result.length(), stripper.getTableCount());
            return result;
        }
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    // ==================== 内部类 ====================

    /**
     * 带坐标的文本片段
     */
    private static class TextFragment {
        final String text;
        final float x;
        final float y;
        final float width;
        final float fontSize;
        final int page;

        TextFragment(String text, float x, float y, float width, float fontSize, int page) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.fontSize = fontSize;
            this.page = page;
        }
    }

    /**
     * 表格感知的 PDFTextStripper
     * <p>
     * 不覆盖 writeString —— 文本提取完全交给 PDFBox 原生实现（保留下标合并）。
     * 仅通过 processTextPosition 收集坐标，用于事后检测表格区域。
     */
    private static class TableAwareStripper extends PDFTextStripper {

        private final List<TextFragment> allFragments = new ArrayList<>();
        private int tableCount = 0;

        TableAwareStripper() throws IOException {
            super();
        }

        int getTableCount() {
            return tableCount;
        }

        /**
         * 收集每个字符的坐标信息（不影响父类文本输出）
         */
        @Override
        protected void processTextPosition(TextPosition text) {
            String unicode = text.getUnicode();
            if (unicode != null && !unicode.isBlank()) {
                allFragments.add(new TextFragment(
                        unicode,
                        text.getX(),
                        text.getY(),
                        text.getWidth(),
                        text.getFontSize(),
                        getCurrentPageNo()
                ));
            }
            // 关键：继续调用父类，让 PDFBox 原生 writeString 正确处理文本
            super.processTextPosition(text);
        }

        // writeString 不覆盖！父类实现保留下标合并、间距计算等

        /**
         * 用坐标数据检测表格，将表格区域替换为 Markdown 格式
         */
        String injectMarkdownTables(String normalText) {
            if (allFragments.isEmpty()) return normalText;

            // 按页分析表格
            Map<Integer, List<TextFragment>> byPage = allFragments.stream()
                    .collect(Collectors.groupingBy(f -> f.page, LinkedHashMap::new, Collectors.toList()));

            // 按页分割原始文本
            String[] pageTexts = normalText.split("\n(?=\\p{javaUpperCase})"); // 粗略分页
            // PDFBox 输出通常不含显式分页符，改按空行+起始特征分割
            List<String> pages = splitPages(normalText, byPage.size());

            StringBuilder result = new StringBuilder();
            int pageIdx = 0;
            for (Map.Entry<Integer, List<TextFragment>> entry : byPage.entrySet()) {
                List<TextFragment> pageFragments = entry.getValue();
                String pageText = pageIdx < pages.size() ? pages.get(pageIdx) : "";
                pageIdx++;

                if (result.length() > 0) result.append("\n");

                // 检测本页的表格
                List<DetectedTable> tables = detectTablesOnPage(pageFragments);
                if (tables.isEmpty()) {
                    result.append(pageText);
                } else {
                    result.append(replaceTablesWithMarkdown(pageText, tables));
                    tableCount += tables.size();
                }
            }
            return result.toString().trim();
        }

        /**
         * 将原始文本按页粗略分割
         */
        private List<String> splitPages(String text, int expectedPages) {
            List<String> pages = new ArrayList<>();
            if (expectedPages <= 1) {
                pages.add(text);
                return pages;
            }
            // 按双换行分割后均匀分到各页（PDFBox 的 getText 已将多页文本合并）
            // 简化处理：如果坐标显示多页但文本没有分隔，就直接用大片空白做分割
            String[] parts = text.split("\n{3,}");
            if (parts.length >= expectedPages) {
                // 足够的分隔符，直接按分隔分配
                Collections.addAll(pages, parts);
            } else {
                // 文本合并紧密，整段作为一页处理
                pages.add(text);
            }
            return pages;
        }

        // ---- 表格检测 ----

        private List<DetectedTable> detectTablesOnPage(List<TextFragment> fragments) {
            // 合并碎片为词
            List<Word> words = fragmentsToWords(fragments);
            // 按 Y 分组为行
            List<TextRow> rows = groupIntoRows(words);
            if (rows.isEmpty()) return Collections.emptyList();

            // 每行检测列
            for (TextRow row : rows) {
                row.detectColumns();
            }

            // 扫描连续行，找出表格
            List<DetectedTable> tables = new ArrayList<>();
            int i = 0;
            while (i < rows.size()) {
                TableMatch match = tryMatchTable(rows, i);
                if (match != null) {
                    tables.add(new DetectedTable(match.data, rows.get(match.startRow),
                            rows.get(match.endRow)));
                    i = match.endRow + 1;
                } else {
                    i++;
                }
            }
            return tables;
        }

        /**
         * 在原始文本中找到表格区域并替换为 Markdown
         */
        private String replaceTablesWithMarkdown(String pageText, List<DetectedTable> tables) {
            StringBuilder result = new StringBuilder();
            int lastEnd = 0;

            for (DetectedTable table : tables) {
                // 用表格起始行的文本内容定位
                String firstLine = table.firstRowText();
                String lastLine = table.lastRowText();

                int tableStart = pageText.indexOf(firstLine, lastEnd);
                if (tableStart < 0) continue; // 定位失败，跳过

                int tableEnd = pageText.indexOf(lastLine, tableStart);
                if (tableEnd < 0) {
                    tableEnd = tableStart + firstLine.length();
                } else {
                    tableEnd += lastLine.length();
                }

                // 前文
                result.append(pageText, lastEnd, tableStart);

                // Markdown 表格
                result.append("\n").append(formatTableToMarkdown(table)).append("\n");

                lastEnd = tableEnd;
            }
            // 尾部
            result.append(pageText.substring(lastEnd));
            return result.toString();
        }

        // ---- 碎片 → 词 ----

        private List<Word> fragmentsToWords(List<TextFragment> fragments) {
            if (fragments.isEmpty()) return Collections.emptyList();

            fragments.sort(Comparator.comparingDouble((TextFragment f) -> f.y)
                    .thenComparingDouble(f -> f.x));

            List<Word> words = new ArrayList<>();
            Word current = null;

            for (TextFragment f : fragments) {
                if (current == null) {
                    current = new Word(f);
                } else if (Math.abs(f.y - current.y) < ROW_Y_TOLERANCE
                        && f.x - (current.x + current.totalWidth) < current.avgCharWidth() * 1.5) {
                    current.append(f);
                } else {
                    words.add(current);
                    current = new Word(f);
                }
            }
            if (current != null) words.add(current);
            return words;
        }

        // ---- 行分组 ----

        private List<TextRow> groupIntoRows(List<Word> words) {
            if (words.isEmpty()) return Collections.emptyList();

            List<TextRow> rows = new ArrayList<>();
            TextRow currentRow = new TextRow();

            for (Word w : words) {
                if (currentRow.words.isEmpty()) {
                    currentRow.words.add(w);
                } else {
                    float avgY = currentRow.averageY();
                    if (Math.abs(w.y - avgY) < ROW_Y_TOLERANCE) {
                        currentRow.words.add(w);
                    } else {
                        rows.add(currentRow);
                        currentRow = new TextRow();
                        currentRow.words.add(w);
                    }
                }
            }
            if (!currentRow.words.isEmpty()) rows.add(currentRow);
            return rows;
        }

        // ---- 表格匹配 ----

        private TableMatch tryMatchTable(List<TextRow> rows, int startRow) {
            TextRow first = rows.get(startRow);
            if (first.columns.size() < 2) return null;

            int colCount = first.columns.size();
            List<List<String>> tableData = new ArrayList<>();
            int endRow = startRow;

            for (int r = startRow; r < rows.size(); r++) {
                TextRow row = rows.get(r);
                if (row.toPlainText().isBlank()) break;
                if (row.columns.size() != colCount) break;
                if (!columnsAligned(first, row, colCount)) break;

                tableData.add(row.columnTexts());
                endRow = r;
            }

            int rowCount = endRow - startRow + 1;
            if (rowCount < MIN_TABLE_ROWS) return null;

            return new TableMatch(tableData, startRow, endRow);
        }

        private boolean columnsAligned(TextRow ref, TextRow other, int colCount) {
            for (int c = 0; c < colCount; c++) {
                if (c >= ref.columns.size() || c >= other.columns.size()) return false;
                if (Math.abs(ref.columns.get(c).x - other.columns.get(c).x) > COL_ALIGN_TOLERANCE) {
                    return false;
                }
            }
            return true;
        }

        private String formatTableToMarkdown(DetectedTable table) {
            List<List<String>> data = table.data;
            if (data.isEmpty()) return "";

            int colCount = data.get(0).size();
            StringBuilder sb = new StringBuilder();

            for (int r = 0; r < data.size(); r++) {
                List<String> row = data.get(r);
                sb.append("| ");
                for (int c = 0; c < colCount; c++) {
                    String cell = c < row.size() ? row.get(c) : "";
                    cell = cell.replace("|", "\\|").replace("\n", " ").trim();
                    sb.append(cell).append(" | ");
                }
                sb.append("\n");
                if (r == 0) {
                    sb.append("|");
                    for (int c = 0; c < colCount; c++) sb.append("---|");
                    sb.append("\n");
                }
            }
            return sb.toString();
        }
    }

    // ==================== 数据结构 ====================

    private static class Word {
        final StringBuilder text = new StringBuilder();
        float x;
        float y;
        float totalWidth;

        Word(TextFragment f) {
            this.text.append(f.text);
            this.x = f.x;
            this.y = f.y;
            this.totalWidth = f.width;
        }

        void append(TextFragment f) {
            this.text.append(f.text);
            this.totalWidth = f.x + f.width - this.x;
        }

        float avgCharWidth() {
            return text.length() > 0 ? totalWidth / text.length() : 5.0f;
        }

        float endX() {
            return x + totalWidth;
        }
    }

    private static class TextRow {
        final List<Word> words = new ArrayList<>();
        List<Column> columns = new ArrayList<>();

        float averageY() {
            return (float) words.stream().mapToDouble(w -> w.y).average().orElse(0);
        }

        void detectColumns() {
            if (words.isEmpty()) return;
            List<Word> sorted = new ArrayList<>(words);
            sorted.sort(Comparator.comparingDouble(w -> w.x));

            float avgCharW = (float) sorted.stream()
                    .mapToDouble(Word::avgCharWidth).average().orElse(5.0);
            float gapThreshold = avgCharW * COLUMN_GAP_RATIO;

            columns = new ArrayList<>();
            Column currentCol = new Column(sorted.get(0).x);

            for (int i = 0; i < sorted.size(); i++) {
                Word w = sorted.get(i);
                if (i > 0) {
                    float gap = w.x - sorted.get(i - 1).endX();
                    if (gap > gapThreshold) {
                        columns.add(currentCol);
                        currentCol = new Column(w.x);
                    }
                }
                currentCol.words.add(w);
            }
            columns.add(currentCol);
        }

        String toPlainText() {
            return words.stream()
                    .sorted(Comparator.comparingDouble(w -> w.x))
                    .map(w -> w.text.toString())
                    .collect(Collectors.joining(" "));
        }

        List<String> columnTexts() {
            return columns.stream()
                    .map(col -> col.words.stream()
                            .sorted(Comparator.comparingDouble(w -> w.x))
                            .map(w -> w.text.toString())
                            .collect(Collectors.joining(" ")))
                    .collect(Collectors.toList());
        }
    }

    private static class Column {
        final float x;
        final List<Word> words = new ArrayList<>();
        Column(float x) { this.x = x; }
    }

    private static class TableMatch {
        final List<List<String>> data;
        final int startRow;
        final int endRow;
        TableMatch(List<List<String>> data, int startRow, int endRow) {
            this.data = data;
            this.startRow = startRow;
            this.endRow = endRow;
        }
    }

    private static class DetectedTable {
        final List<List<String>> data;
        final TextRow firstRow;
        final TextRow lastRow;

        DetectedTable(List<List<String>> data, TextRow firstRow, TextRow lastRow) {
            this.data = data;
            this.firstRow = firstRow;
            this.lastRow = lastRow;
        }

        String firstRowText() {
            return firstRow.toPlainText();
        }

        String lastRowText() {
            return lastRow.toPlainText();
        }
    }
}
