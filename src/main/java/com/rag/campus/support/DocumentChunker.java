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

        // 过滤目录页 — 查找"长section → 连续短section → 长section"的 TOC 夹层
        // 如果找不到夹层，回退到跳过开头所有短section
        int tocStart = -1;
        int tocEnd = -1;
        boolean seenLong = false;

        for (int i = 0; i < sections.size(); i++) {
            boolean isLong = sections.get(i).length() >= 200;
            if (isLong) {
                if (tocStart >= 0) {
                    tocEnd = i;
                    break;
                }
                seenLong = true;
            } else if (seenLong && tocStart < 0) {
                // 在长section之后第一次遇到短section → TOC 开始
                tocStart = i;
            }
        }

        if (tocStart > 0 && tocEnd > tocStart) {
            // 找到 TOC 夹层：删除 [tocStart, tocEnd)
            List<String> filtered = new ArrayList<>(sections.subList(0, tocStart));
            filtered.addAll(sections.subList(tocEnd, sections.size()));
            sections = filtered;
        } else {
            // 无夹层，回退：跳过开头所有短section
            int firstReal = 0;
            for (int i = 0; i < sections.size(); i++) {
                if (sections.get(i).length() >= 200) {
                    firstReal = i;
                    break;
                }
            }
            if (firstReal > 0) {
                sections = new ArrayList<>(sections.subList(firstReal, sections.size()));
            }
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

    /**
     * 判断一个 Q section 是否为目录条目
     * <p>
     * 目录条目特征（满足任一即判定为目录）：
     * 1. 极短：< 80 字
     * 2. 省略号引导符：含 ... / …… / …（如 "Q1 校园卡..........42"）
     * 3. 短条目以页码结尾：长度 < 200 且末尾是空白/点号+数字
     * 4. 纯问题无答案：长度 < 150 且不含句号/分号
     */
    private boolean isTocEntry(String section) {
        String trimmed = section.trim();
        int len = trimmed.length();

        // 1. 极短
        if (len < 80) return true;

        // 2. 含省略号引导符（…… 或 ... 或 . . . 或单个…）
        if (trimmed.contains("...") || trimmed.contains("……")
                || trimmed.contains("…") || trimmed.contains(". . .")) return true;

        // 3. 短条目以页码结尾（如 ".....42"、" 42"、"……42"）
        if (len < 200 && trimmed.matches("(?s).*[.。…\\s]+\\d{1,3}\\s*$")) return true;

        // 4. 仅含问题无答案：短于150字且没有句号/分号（只有 Q 标题，无回答正文）
        if (len < 150 && !trimmed.contains("。") && !trimmed.contains("；")) return true;

        return false;
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

    // ==================== 策略三：滑动窗口（兜底，Markdown 表格感知） ====================

    /**
     * 滑动窗口分块，支持 Markdown 表格感知。
     * <p>
     * 当表格跨 chunk 拆分时，新 chunk 开头自动补回表头行 + 分隔线，
     * 确保每个 chunk 中的表格数据行都自带列名上下文。
     * <p>
     * 表格识别规则：以 | 开头且以 | 结尾的行视为表格行；
     * 第一行 = 表头，紧跟的 |---| 行 = 分隔线。
     */
    private List<String> chunkBySlidingWindow(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

        String[] paragraphs = text.split("\n");
        StringBuilder currentChunk = new StringBuilder();

        // 表格状态追踪
        String pendingHeader = null;   // 候选表头行，等待分隔线确认
        String tableHeader = null;     // 已确认的"表头\n分隔线"，用于跨 chunk 补偿
        boolean inTable = false;       // 当前是否在表格数据区内

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();

            // 空行结束表格上下文
            if (trimmed.isEmpty()) {
                pendingHeader = null;
                tableHeader = null;
                inTable = false;
                continue;
            }

            // 检测 Markdown 表格行
            boolean isTableLine = trimmed.startsWith("|") && trimmed.endsWith("|");
            boolean isSeparator = isTableLine && trimmed.contains("---");

            // 表格状态机
            if (isTableLine) {
                if (isSeparator && pendingHeader != null) {
                    // 确认表格：pendingHeader 是表头，当前行是分隔线
                    tableHeader = pendingHeader + "\n" + trimmed;
                    pendingHeader = null;
                    inTable = true;
                } else if (!isSeparator && !inTable) {
                    // 候选表头（等待下一行分隔线确认）
                    pendingHeader = trimmed;
                }
            } else {
                // 非表格行，重置状态
                pendingHeader = null;
                tableHeader = null;
                inTable = false;
            }

            // --- 添加到当前 chunk ---
            if (currentChunk.length() + trimmed.length() <= chunkSize) {
                if (currentChunk.length() > 0) currentChunk.append("\n");
                currentChunk.append(trimmed);
            } else {
                // 当前 chunk 已满，保存并开启新 chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());

                    StringBuilder newChunk = new StringBuilder();

                    // ★ 关键：如果新 chunk 从表格数据行开始，补回表头上下文
                    if (inTable && isTableLine && !isSeparator && tableHeader != null) {
                        newChunk.append(tableHeader).append("\n");
                    } else {
                        // 普通文本：使用字符级重叠防止信息丢失
                        String prevText = currentChunk.toString();
                        if (prevText.length() > overlap) {
                            newChunk.append(prevText.substring(prevText.length() - overlap));
                        }
                    }

                    currentChunk = newChunk;
                }

                // 超长段落处理
                if (trimmed.length() > chunkSize) {
                    chunks.addAll(splitLongParagraph(trimmed));
                    currentChunk = new StringBuilder();
                } else {
                    if (currentChunk.length() > 0) currentChunk.append("\n");
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
