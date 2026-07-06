package com.rag.campus.support;

import java.io.IOException;
import java.util.Set;

/**
 * 文档转换器 — 将各种格式文件统一转为纯文本
 * <p>
 * 面试要点：
 * 1. 策略模式：不同文件格式对应不同实现，新增格式不改现有代码（开闭原则）
 * 2. 接口隔离：Service 只依赖接口，具体实现通过 Spring DI 注入
 * 3. 未来扩展：新增 MarkItDownConverter 将 PDF/Word → Markdown，
 *    后续分块全部走 Markdown # 标题切分，无需再区分文件格式
 */
public interface DocumentConverter {

    /**
     * 将文件字节内容转换为纯文本（或 Markdown 格式文本）
     *
     * @param fileBytes 文件原始字节
     * @param filename  原始文件名（用于判断扩展名）
     * @return 提取后的文本内容
     * @throws IOException 文件读取或转换失败
     */
    String convert(byte[] fileBytes, String filename) throws IOException;

    /**
     * 此转换器支持的文件扩展名集合（小写，含点）
     * 例: Set.of(".pdf")、Set.of(".txt", ".md")
     */
    Set<String> supportedExtensions();
}
