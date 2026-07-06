package com.rag.campus.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档分块器 — 多策略自适应
 * <p>
 * 策略优先级：
 *   1. Markdown 标题切分 — 文本含 # / ## / ### 标记 → 按标题层级切分
 *   2. 中文结构切分       — 政策文档含"第X章""第X条" → 按章节边界切分
 *   3. 滑动窗口           — 兜底策略，段落优先 + 固定大小
 * <p>
 * 面试要点：
 * 1. 为什么 Markdown 最优？— # 标记是显式结构信号，不会误判，所有文档类型通用
 * 2. 为什么保留中文结构切分？— 很多政策 PDF 提取后不带 Markdown，但保留"第X章"格式
 * 3. 为什么需要兜底？— 生活指南、FAQ 等自由文本没有固定结构
 */
public class DocumentChunker {

    private final int chunkSize;
    private final int overlap;

    /** 中文结构切分适用的文档分类 */
    private static final Set<String> STRUCTURED_CATEGORIES = new HashSet<>(Arrays.asList(
            "POLICY", "SCHOLARSHIP", "ACADEMIC"
    ));

    /** 匹配 Markdown ATX 标题: # / ## / ### ... */
    private static final Pattern MD_HEADING_PATTERN = Pattern.compile(
            "^#{1,6}\\s+[^#\\s]", Pattern.MULTILINE
    );

    /** 匹配 "第X章"、"第X节"、"第X条" 等中文结构标记 */
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "(?:^|\\n)\\s*(第[一二三四五六七八九十百千\\d]+[章节条部分编])"
    );

    /** 匹配 Q&A 格式标题: Q1 / Q2 / ... 后跟空格/冒号/顿号 */
    private static final Pattern QA_HEADING_PATTERN = Pattern.compile(
            "^Q\\d+[\\s：、.:，]", Pattern.MULTILINE
    );

    /** 章节标题最大长度（超过视为总结句） */
    private static final int MAX_HEADING_LENGTH = 60;

    /** 总结句关键词 */
    private static final Pattern SUMMARY_FOLLOW_PATTERN = Pattern.compile(
            ".*(介绍|说明|阐述|描述|讲述|讨论|分析|总结).*"
    );

    public DocumentChunker(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    // ==================== 主入口 ====================

    /**
     * 按分类 + 内容特征自动选择策略
     */
    public List<String> chunk(String text, String category) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        // 优先级1：检测 Markdown 标题标记
        if (hasMarkdownHeadings(text)) {
            return chunkByMarkdown(text);
        }

        // 优先级2：检测 Q&A 格式（Q1/Q2/Q3...）
        if (hasQAHeadings(text)) {
            return chunkByQA(text);
        }

        // 优先级3：政策文档走中文结构切分
        if (STRUCTURED_CATEGORIES.contains(category)) {
            List<String> chunks = chunkByStructure(text);
            if (chunks.size() > 1) {
                return chunks;
            }
        }

        // 优先级4：滑动窗口兜底
        return chunkBySlidingWindow(text);
    }

    /** 兼容旧调用 */
    public List<String> chunk(String text) {
        if (text != null) {
            if (hasMarkdownHeadings(text)) return chunkByMarkdown(text);
            if (hasQAHeadings(text)) return chunkByQA(text);
        }
        return chunkBySlidingWindow(text);
    }

    // ==================== 策略一：Markdown 标题切分 ====================

    /**
     * 检测文本是否包含 Markdown 标题标记
     */
    private boolean hasMarkdownHeadings(String text) {
        return MD_HEADING_PATTERN.matcher(text).find();
    }

    /**
     * 按 Markdown 标题层级切分
     * <p>
     * 每个 # 标题 + 其下的内容 = 一个 section，
     * section 短于 chunkSize 直接作为 chunk，超长则内部滑动窗口
     */
    private List<String> chunkByMarkdown(String text) {
        // 1. 找到所有标题行位置，切分为 section
        Matcher matcher = MD_HEADING_PATTERN.matcher(text);
        List<Integer> headingPositions = new ArrayList<>();

        while (matcher.find()) {
            headingPositions.add(matcher.start());
        }

        if (headingPositions.isEmpty()) {
            // 没有标题，整篇作为一段
            return chunkBySlidingWindow(text);
        }

        // 2. 按标题位置切分
        List<String> sections = new ArrayList<>();
        for (int i = 0; i < headingPositions.size(); i++) {
            int start = headingPositions.get(i);
            int end = (i + 1 < headingPositions.size())
                    ? headingPositions.get(i + 1)
                    : text.length();
            String section = text.substring(start, end).trim();
            if (!section.isEmpty()) {
                sections.add(section);
            }
        }

        // 3. 标题之前的内容（前言/摘要）也收集
        if (!headingPositions.isEmpty() && headingPositions.get(0) > 0) {
            String preamble = text.substring(0, headingPositions.get(0)).trim();
            if (!preamble.isEmpty()) {
                sections.add(0, preamble);
            }
        }

        // 4. 每段：短则直接使用，长则滑动窗口
        List<String> chunks = new ArrayList<>();
        for (String section : sections) {
            if (section.length() <= chunkSize) {
                chunks.add(section);
            } else {
                chunks.addAll(chunkBySlidingWindow(section));
            }
        }

        return chunks;
    }

    // ==================== 策略二：Q&A 格式切分 ====================

    private boolean hasQAHeadings(String text) {
        return QA_HEADING_PATTERN.matcher(text).find();
    }

    /**
     * 按 Q1/Q2/Q3 标题切分
     * <p>
     * 常见于新生入学指南、FAQ 类文档，每个 Q 是一个独立问答。
     * 逻辑与 Markdown 切分类似：找到所有 Q 标题位置，按边界切分，
     * 每段短于 chunkSize 直接使用，超长内部滑动窗口。
     */
    private List<String> chunkByQA(String text) {
        Matcher matcher = QA_HEADING_PATTERN.matcher(text);
        List<Integer> headingPositions = new ArrayList<>();

        while (matcher.find()) {
            // 找到 Q 标记所在行的行首
            int pos = matcher.start();
            while (pos > 0 && text.charAt(pos - 1) != '\n') {
                pos--;
            }
            headingPositions.add(pos);
        }

        if (headingPositions.isEmpty()) {
            return chunkBySlidingWindow(text);
        }

        // 按标题位置切分
        List<String> sections = new ArrayList<>();
        for (int i = 0; i < headingPositions.size(); i++) {
            int start = headingPositions.get(i);
            int end = (i + 1 < headingPositions.size())
                    ? headingPositions.get(i + 1)
                    : text.length();
            String section = text.substring(start, end).trim();
            if (!section.isEmpty()) {
                sections.add(section);
            }
        }

        // Q 标题之前的前言/目录
        if (headingPositions.get(0) > 0) {
            String preamble = text.substring(0, headingPositions.get(0)).trim();
            if (!preamble.isEmpty() && preamble.length() >= 80) {
                sections.add(0, preamble);
            }
        }

        // 过滤目录条目：开头连续的超短 section（< 80 字，含页码）视为 TOC，跳过
        int firstRealSection = 0;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).length() >= 80) {
                firstRealSection = i;
                break;
            }
        }
        if (firstRealSection > 0) {
            sections = sections.subList(firstRealSection, sections.size());
        }

        List<String> chunks = new ArrayList<>();
        for (String section : sections) {
            if (section.length() <= chunkSize) {
                chunks.add(section);
            } else {
                chunks.addAll(chunkBySlidingWindow(section));
            }
        }
        return chunks;
    }

    // ==================== 策略三：中文结构切分 ====================

    /**
     * 按"第X章""第X条"边界切分（用于从 PDF 提取的非 Markdown 政策文档）
     */
    private List<String> chunkByStructure(String text) {
        Matcher matcher = SECTION_PATTERN.matcher(text);
        List<Integer> cutPoints = new ArrayList<>();

        while (matcher.find()) {
            int matchStart = matcher.start();

            // 定位所在行
            int lineStart = matchStart;
            while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
                lineStart--;
            }
            int lineEnd = matchStart;
            while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') {
                lineEnd++;
            }
            String line = text.substring(lineStart, lineEnd).trim();

            // 过滤假阳性
            if (line.length() > MAX_HEADING_LENGTH) continue;
            if (SUMMARY_FOLLOW_PATTERN.matcher(line).matches()) continue;

            int pos = matchStart;
            if (pos > 0 && text.charAt(pos) == '\n') pos++;
            if (pos > 0 && !cutPoints.contains(pos)) {
                cutPoints.add(pos);
            }
        }

        if (cutPoints.isEmpty()) {
            List<String> single = new ArrayList<>();
            single.add(text);
            return single;
        }

        // 精确切分
        List<String> sections = new ArrayList<>();
        int start = 0;
        for (int cut : cutPoints) {
            if (cut > start) {
                int actualCut = cut;
                while (actualCut > start && text.charAt(actualCut - 1) != '\n') {
                    actualCut--;
                }
                if (actualCut > start) {
                    sections.add(text.substring(start, actualCut).trim());
                    start = actualCut;
                }
            }
        }
        if (start < text.length()) {
            sections.add(text.substring(start).trim());
        }

        List<String> chunks = new ArrayList<>();
        for (String section : sections) {
            if (section.isEmpty()) continue;
            if (section.length() <= chunkSize) {
                chunks.add(section);
            } else {
                chunks.addAll(chunkBySlidingWindow(section));
            }
        }
        return chunks;
    }

    // ==================== 策略三：滑动窗口（兜底） ====================

    private List<String> chunkBySlidingWindow(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

        String[] paragraphs = text.split("\n");
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) continue;

            if (currentChunk.length() + trimmed.length() <= chunkSize) {
                if (currentChunk.length() > 0) currentChunk.append("\n");
                currentChunk.append(trimmed);
            } else {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    String prevText = currentChunk.toString();
                    currentChunk = new StringBuilder(
                            prevText.length() > overlap
                                    ? prevText.substring(prevText.length() - overlap)
                                    : "");
                }
                if (trimmed.length() > chunkSize) {
                    chunks.addAll(splitLongParagraph(trimmed));
                    currentChunk = new StringBuilder();
                } else {
                    currentChunk.append(trimmed);
                }
            }
        }

        if (currentChunk.length() > 0) chunks.add(currentChunk.toString());
        return chunks;
    }

    private List<String> splitLongParagraph(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            result.add(text.substring(start, end));
            start += (chunkSize - overlap);
        }
        return result;
    }
}
