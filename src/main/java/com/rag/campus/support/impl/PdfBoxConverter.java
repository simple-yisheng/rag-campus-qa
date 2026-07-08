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
 * 通过分析文本坐标检测表格结构，将表格区域输出为 Markdown 表格格式，
 * 使下游 DocumentChunker 的表格感知切分能够正确保留行列上下文。
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

            // 第一遍：用自定义 stripper 收集带坐标的文本块
            TableAwareStripper stripper = new TableAwareStripper();
            stripper.setSortByPosition(true);
            stripper.getText(pdfDoc); // 触发 writeString 收集数据

            // 第二遍：分析坐标，检测表格，输出结构化文本
            String result = stripper.buildStructuredText();

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
        final int page;

        TextFragment(String text, float x, float y, float width, int page) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.page = page;
        }
    }

    /**
     * 表格检测 + 结构化文本输出的自定义 PDFTextStripper
     * <p>
     * 重写 writeString 收集所有文本片段的坐标信息，
     * 然后按页分析行列结构，检测表格并输出 Markdown 格式。
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

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (textPositions == null || textPositions.isEmpty()) return;

            int currentPage = getCurrentPageNo();
            for (TextPosition pos : textPositions) {
                String unicode = pos.getUnicode();
                if (unicode == null || unicode.isBlank()) continue;
                allFragments.add(new TextFragment(
                        unicode,
                        pos.getX(),
                        pos.getY(),
                        pos.getWidth(),
                        currentPage
                ));
            }
        }

        /**
         * 分析收集到的文本坐标，检测表格并输出结构化文本
         */
        String buildStructuredText() {
            if (allFragments.isEmpty()) return "";

            // 按页分组
            Map<Integer, List<TextFragment>> byPage = allFragments.stream()
                    .collect(Collectors.groupingBy(f -> f.page, LinkedHashMap::new, Collectors.toList()));

            StringBuilder result = new StringBuilder();
            for (Map.Entry<Integer, List<TextFragment>> entry : byPage.entrySet()) {
                if (result.length() > 0) result.append("\n");
                result.append(processPage(entry.getValue()));
            }
            return result.toString().trim();
        }

        /**
         * 处理单页：检测表格并输出
         */
        private String processPage(List<TextFragment> fragments) {
            // 1. 合并同一行的碎片为词
            List<Word> words = fragmentsToWords(fragments);

            // 2. 按 Y 坐标分组为行
            List<TextRow> rows = groupIntoRows(words);
            if (rows.isEmpty()) return "";

            // 3. 为每行检测列分割点
            for (TextRow row : rows) {
                row.detectColumns();
            }

            // 4. 扫描连续行，识别表格区域
            return buildPageText(rows);
        }

        // ---- 碎片合并为词 ----

        private List<Word> fragmentsToWords(List<TextFragment> fragments) {
            if (fragments.isEmpty()) return Collections.emptyList();

            // 按 Y 排序，再按 X 排序
            fragments.sort(Comparator.comparingDouble((TextFragment f) -> f.y)
                    .thenComparingDouble(f -> f.x));

            List<Word> words = new ArrayList<>();
            Word current = null;

            for (TextFragment f : fragments) {
                if (current == null) {
                    current = new Word(f);
                } else if (Math.abs(f.y - current.y) < ROW_Y_TOLERANCE
                        && f.x - (current.x + current.totalWidth) < current.avgCharWidth() * 1.5) {
                    // 同一行、间距合理 → 合并到当前词
                    current.append(f);
                } else {
                    // 新词
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

        // ---- 构建页面文本 ----

        private String buildPageText(List<TextRow> rows) {
            StringBuilder sb = new StringBuilder();

            int i = 0;
            while (i < rows.size()) {
                // 尝试从此行开始检测表格
                TableResult table = tryExtractTable(rows, i);
                if (table != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(formatMarkdownTable(table));
                    i = table.endRow + 1;
                    tableCount++;
                } else {
                    // 普通文本行
                    String line = rows.get(i).toPlainText();
                    if (!line.isBlank()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(line);
                    }
                    i++;
                }
            }
            return sb.toString();
        }

        /**
         * 尝试从 startRow 开始检测表格
         * <p>
         * 判定条件：
         * 1. 连续至少 MIN_TABLE_ROWS 行
         * 2. 每行列数相同（>= 2 列）
         * 3. 各列 X 坐标跨行对齐
         *
         * @return 检测到的表格，null 表示不是表格
         */
        private TableResult tryExtractTable(List<TextRow> rows, int startRow) {
            TextRow first = rows.get(startRow);
            if (first.columns.size() < 2) return null;

            int colCount = first.columns.size();
            List<List<String>> tableData = new ArrayList<>();
            int endRow = startRow;

            for (int r = startRow; r < rows.size(); r++) {
                TextRow row = rows.get(r);

                // 空行结束表格
                if (row.toPlainText().isBlank()) break;

                // 列数必须匹配
                if (row.columns.size() != colCount) break;

                // X 对齐检查（每列起始坐标与首行对齐）
                if (!columnsAligned(first, row, colCount)) break;

                tableData.add(row.columnTexts());
                endRow = r;
            }

            int rowCount = endRow - startRow + 1;
            if (rowCount < MIN_TABLE_ROWS) return null;

            return new TableResult(tableData, startRow, endRow);
        }

        /**
         * 检查两行的列 X 坐标是否对齐
         */
        private boolean columnsAligned(TextRow reference, TextRow other, int colCount) {
            for (int c = 0; c < colCount; c++) {
                if (c >= reference.columns.size() || c >= other.columns.size()) return false;
                if (Math.abs(reference.columns.get(c).x - other.columns.get(c).x) > COL_ALIGN_TOLERANCE) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 将表格数据格式化为 Markdown 表格
         */
        private String formatMarkdownTable(TableResult table) {
            List<List<String>> data = table.data;
            if (data.isEmpty()) return "";

            int colCount = data.get(0).size();
            StringBuilder sb = new StringBuilder();

            for (int r = 0; r < data.size(); r++) {
                List<String> row = data.get(r);
                sb.append("| ");
                for (int c = 0; c < colCount; c++) {
                    String cell = c < row.size() ? row.get(c) : "";
                    // 转义管道符，合并空白
                    cell = cell.replace("|", "\\|").replace("\n", " ").trim();
                    sb.append(cell).append(" | ");
                }
                sb.append("\n");

                // 第一行后插入分隔线
                if (r == 0) {
                    sb.append("|");
                    for (int c = 0; c < colCount; c++) {
                        sb.append("---|");
                    }
                    sb.append("\n");
                }
            }
            return sb.toString();
        }
    }

    // ==================== 数据结构 ====================

    /**
     * 合并后的词（一行内相邻的文本碎片合并为一个语义单元）
     */
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

        /** 词末尾的 X 坐标 */
        float endX() {
            return x + totalWidth;
        }
    }

    /**
     * 文本行
     */
    private static class TextRow {
        final List<Word> words = new ArrayList<>();
        List<Column> columns = new ArrayList<>();

        float averageY() {
            return (float) words.stream().mapToDouble(w -> w.y).average().orElse(0);
        }

        /** 检测行内的列分割点：按 X 坐标排序后，间距超过阈值的视为列边界 */
        void detectColumns() {
            if (words.isEmpty()) return;

            // 按 X 排序
            List<Word> sorted = new ArrayList<>(words);
            sorted.sort(Comparator.comparingDouble(w -> w.x));

            // 计算列分割的间距阈值
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
                        // 列分割
                        columns.add(currentCol);
                        currentCol = new Column(w.x);
                    }
                }
                currentCol.words.add(w);
            }
            columns.add(currentCol);
        }

        /** 输出为纯文本（非表格行） */
        String toPlainText() {
            return words.stream()
                    .sorted(Comparator.comparingDouble(w -> w.x))
                    .map(w -> w.text.toString())
                    .collect(Collectors.joining(" "));
        }

        /** 输出各列文本 */
        List<String> columnTexts() {
            return columns.stream()
                    .map(col -> col.words.stream()
                            .sorted(Comparator.comparingDouble(w -> w.x))
                            .map(w -> w.text.toString())
                            .collect(Collectors.joining(" ")))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 表格中的一列
     */
    private static class Column {
        final float x;
        final List<Word> words = new ArrayList<>();

        Column(float x) {
            this.x = x;
        }
    }

    /**
     * 检测到的表格结果
     */
    private static class TableResult {
        final List<List<String>> data;
        final int startRow;
        final int endRow;

        TableResult(List<List<String>> data, int startRow, int endRow) {
            this.data = data;
            this.startRow = startRow;
            this.endRow = endRow;
        }
    }
}
