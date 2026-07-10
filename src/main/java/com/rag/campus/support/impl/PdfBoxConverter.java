package com.rag.campus.support.impl;

import com.rag.campus.client.QwenVisionClient;
import com.rag.campus.support.DocumentConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PDF 文本提取器（基于 Apache PDFBox）
 * <p>
 * 支持三种 PDF 类型：
 * <ol>
 *   <li><b>普通 PDF</b> — PDFBox 提取文字 + 坐标分析检测表格 → Markdown</li>
 *   <li><b>含图片的 PDF</b> — 提取嵌入图片 → 通义千问 VL 描述 → 注入文档文本</li>
 *   <li><b>扫描件 PDF</b> — 检测文字为空 → 渲染页面为图片 → VL OCR 识别</li>
 * </ol>
 * <p>
 * 面试要点：
 * 1. 扫描件检测：PDFBox 提取不到文字 → 判定为扫描件 → OCR 兜底
 * 2. 图片信息不丢失：多模态 LLM 将图片转为文字描述，RAG 可检索
 * 3. 优雅降级：VL API 失败不影响核心文本提取
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

    /** 判定为扫描件的文字长度阈值（全文档） */
    private static final int SCANNED_TEXT_THRESHOLD = 100;

    /** 扫描件页面渲染 DPI（平衡清晰度与 API 调用成本） */
    private static final int SCAN_RENDER_DPI = 150;

    /** 提取图片的最小尺寸（小于此尺寸的忽略，如图标、装饰元素） */
    private static final int MIN_IMAGE_WIDTH = 100;
    private static final int MIN_IMAGE_HEIGHT = 100;

    /** 每篇文档最多提取的图片数量（控制 API 成本） */
    private static final int MAX_IMAGES_PER_DOC = 6;

    /** 扫描件最多 OCR 的页数 */
    private static final int MAX_OCR_PAGES = 20;

    private final QwenVisionClient visionClient;

    @Value("${rag.vision.enabled:true}")
    private boolean visionEnabled;

    @Value("${rag.vision.max-images:6}")
    private int maxImagesPerDoc;

    public PdfBoxConverter(QwenVisionClient visionClient) {
        this.visionClient = visionClient;
    }

    @Override
    public String convert(byte[] fileBytes, String filename) throws IOException {
        try (PDDocument pdfDoc = PDDocument.load(fileBytes)) {
            int totalPages = pdfDoc.getNumberOfPages();

            // === 第一步：普通文本提取 + 表格检测 ===
            TableAwareStripper stripper = new TableAwareStripper();
            stripper.setSortByPosition(true);
            String normalText = stripper.getText(pdfDoc);
            String result = stripper.injectMarkdownTables(normalText);
            int extractedLen = result.replaceAll("\\s+", "").length();

            // === 第二步：判断是否为扫描件 ===
            if (extractedLen < SCANNED_TEXT_THRESHOLD && totalPages > 0) {
                log.info("检测到扫描件 PDF: {}, 页数={}, 文本长度={}", filename, totalPages, extractedLen);
                if (visionEnabled) {
                    return ocrScannedPdf(pdfDoc, fileBytes, filename);
                } else {
                    throw new IOException("PDF 无可提取的文字内容，可能是扫描版 PDF。"
                            + "如需 OCR 识别，请在 application.yaml 中启用 rag.vision.enabled=true");
                }
            }

            // === 第三步：提取并描述嵌入图片（普通 PDF） ===
            if (visionEnabled && extractedLen > 0) {
                String imageDescriptions = extractAndDescribeImages(pdfDoc, fileBytes, filename);
                if (!imageDescriptions.isEmpty()) {
                    result = result + "\n\n## 文档附图说明\n" + imageDescriptions;
                }
            }

            if (result.isBlank()) {
                throw new IOException("PDF 文件无可提取的文字内容");
            }

            log.info("PDF 文本提取成功: {}, 页数={}, 字符数={}, 表格数={}",
                    filename, totalPages, result.length(), stripper.getTableCount());
            return result;

        }
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    // ==================== 扫描件 OCR ====================

    /**
     * 对扫描版 PDF 逐页 OCR 识别
     * <p>
     * 用 PDFBox PDFRenderer 将每页渲染为图片，
     * 发送给通义千问 VL 进行 OCR 文字识别。
     */
    private String ocrScannedPdf(PDDocument pdfDoc, byte[] fileBytes, String filename) {
        int totalPages = pdfDoc.getNumberOfPages();
        int pagesToProcess = Math.min(totalPages, MAX_OCR_PAGES);
        StringBuilder fullText = new StringBuilder();

        log.info("扫描件 OCR 开始: {}, 总页数={}, 处理页数={}", filename, totalPages, pagesToProcess);

        // 重新加载文档给 PDFRenderer（原 doc 被 stripper 消费过，需要重新打开）
        try (PDDocument renderDoc = PDDocument.load(fileBytes)) {
            PDFRenderer renderer = new PDFRenderer(renderDoc);

            for (int page = 0; page < pagesToProcess; page++) {
                try {
                    // 渲染页面为图片
                    BufferedImage image = renderer.renderImageWithDPI(page, SCAN_RENDER_DPI);
                    byte[] pngBytes = bufferedImageToPngBytes(image);

                    log.debug("OCR 第 {}/{} 页, 图片大小={}KB", page + 1, pagesToProcess, pngBytes.length / 1024);

                    String pageText = visionClient.ocrPage(pngBytes);
                    if (!pageText.isEmpty()) {
                        fullText.append("第").append(page + 1).append("页\n");
                        fullText.append(pageText).append("\n\n");
                    } else {
                        log.warn("扫描件第 {} 页 OCR 返回空", page + 1);
                    }

                    // 页间延迟，避免触发 QPS 限流
                    if (page < pagesToProcess - 1) {
                        Thread.sleep(300);
                    }

                } catch (Exception e) {
                    log.warn("扫描件第 {} 页 OCR 失败: {}", page + 1, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("扫描件 OCR 渲染失败", e);
        }

        String result = fullText.toString().trim();
        if (result.isEmpty()) {
            log.error("扫描件 OCR 全部失败: {}", filename);
            return "【扫描件 OCR 识别失败】该文档为图片型 PDF，自动文字识别未成功，建议手动录入文字内容。";
        }

        log.info("扫描件 OCR 完成: {}, 总字符数={}", filename, result.length());
        return result;
    }

    // ==================== 嵌入图片处理 ====================

    /**
     * 提取 PDF 中嵌入的图片，用 VL 模型生成文字描述
     */
    private String extractAndDescribeImages(PDDocument pdfDoc, byte[] fileBytes, String filename) {
        List<byte[]> images = new ArrayList<>();

        try {
            for (PDPage page : pdfDoc.getPages()) {
                if (images.size() >= maxImagesPerDoc) break;

                PDResources resources = page.getResources();
                if (resources == null) continue;

                for (org.apache.pdfbox.cos.COSName name : resources.getXObjectNames()) {
                    if (images.size() >= maxImagesPerDoc) break;
                    try {
                        if (!resources.isImageXObject(name)) continue;
                        PDImageXObject image = (PDImageXObject) resources.getXObject(name);

                        // 过滤太小的图片（图标、装饰元素等）
                        if (image.getWidth() < MIN_IMAGE_WIDTH || image.getHeight() < MIN_IMAGE_HEIGHT) {
                            continue;
                        }

                        BufferedImage buffered = image.getImage();
                        byte[] pngBytes = bufferedImageToPngBytes(buffered);
                        if (pngBytes.length > 0) {
                            images.add(pngBytes);
                        }
                    } catch (Exception e) {
                        log.debug("提取图片失败: name={}, {}", name.getName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("遍历 PDF 图片资源失败: {}", e.getMessage());
        }

        if (images.isEmpty()) {
            return "";
        }

        log.info("PDF 提取到 {} 张嵌入图片: {}", images.size(), filename);

        // 逐张描述
        StringBuilder descriptions = new StringBuilder();
        for (int i = 0; i < images.size(); i++) {
            try {
                String mimeType = "image/png";
                String context = "第 " + (i + 1) + " 张附图";
                String desc = visionClient.describeImage(images.get(i), mimeType, context);
                if (!desc.isEmpty()) {
                    descriptions.append("\n**附图 ").append(i + 1).append("**：").append(desc).append("\n");
                } else {
                    log.warn("图片 {} 描述返回空", i + 1);
                }

                // 图片间延迟
                if (i < images.size() - 1) {
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                log.warn("图片 {} 描述失败: {}", i + 1, e.getMessage());
            }
        }

        log.info("PDF 图片描述完成: {}, 图片数={}, 描述字符数={}",
                filename, images.size(), descriptions.length());
        return descriptions.toString();
    }

    // ==================== 工具方法 ====================

    /**
     * BufferedImage → PNG 字节数组
     */
    private byte[] bufferedImageToPngBytes(BufferedImage image) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.warn("图片转 PNG 失败: {}", e.getMessage());
            return new byte[0];
        }
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
