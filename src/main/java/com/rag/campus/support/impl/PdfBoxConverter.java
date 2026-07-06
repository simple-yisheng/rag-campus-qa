package com.rag.campus.support.impl;

import com.rag.campus.support.DocumentConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * PDF 文本提取器（基于 Apache PDFBox）
 * <p>
 * 将 PDF 二进制内容提取为纯文本，保留换行和段落结构。
 * 注意：扫描版 PDF（图片型）无法提取，需 OCR。
 */
@Slf4j
@Component
public class PdfBoxConverter implements DocumentConverter {

    private static final Set<String> EXTENSIONS = Set.of(".pdf");

    @Override
    public String convert(byte[] fileBytes, String filename) throws IOException {
        try (PDDocument pdfDoc = PDDocument.load(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String extracted = stripper.getText(pdfDoc);
            if (extracted.isBlank()) {
                throw new IOException("PDF文件无可提取的文字内容，可能是扫描版PDF");
            }
            log.info("PDF文本提取成功: {}, 字符数={}", filename, extracted.length());
            return extracted;
        }
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }
}
