package com.rag.campus.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Office 文档预览生成服务。
 * <p>
 * 使用 LibreOffice headless 将 Word 转为 PDF，供前端 PDF.js 统一预览。
 */
@Slf4j
@Component
public class OfficePreviewService {

    @Value("${office.preview.enabled:true}")
    private boolean enabled;

    @Value("${office.preview.libreoffice-path:soffice}")
    private String libreOfficePath;

    @Value("${office.preview.timeout-seconds:60}")
    private long timeoutSeconds;

    public byte[] convertWordToPdf(byte[] fileBytes, String filename) throws IOException, InterruptedException {
        if (!enabled) {
            throw new IOException("Office预览转换未启用");
        }

        Path tempDir = Files.createTempDirectory("rag-office-preview-");
        try {
            Path input = tempDir.resolve(sanitizeFilename(filename));
            Files.write(input, fileBytes);

            ProcessBuilder builder = new ProcessBuilder(
                    libreOfficePath,
                    "--headless",
                    "--convert-to", "pdf",
                    "--outdir", tempDir.toString(),
                    input.toString()
            );
            builder.redirectErrorStream(true);

            Process process = builder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("LibreOffice转换超时: " + Duration.ofSeconds(timeoutSeconds));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new IOException("LibreOffice转换失败，exitCode=" + exitCode + ", output=" + output);
            }

            Path pdf = findGeneratedPdf(tempDir, input);
            byte[] pdfBytes = Files.readAllBytes(pdf);
            log.info("Word预览PDF生成成功: filename={}, size={} bytes", filename, pdfBytes.length);
            return pdfBytes;
        } finally {
            deleteDirectoryQuietly(tempDir);
        }
    }

    private Path findGeneratedPdf(Path tempDir, Path input) throws IOException {
        String inputName = input.getFileName().toString();
        int dotIndex = inputName.lastIndexOf('.');
        String baseName = dotIndex >= 0 ? inputName.substring(0, dotIndex) : inputName;
        Path expected = tempDir.resolve(baseName + ".pdf");
        if (Files.exists(expected)) {
            return expected;
        }

        try (var stream = Files.list(tempDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("LibreOffice未生成PDF文件"));
        }
    }

    private String sanitizeFilename(String filename) {
        String name = filename == null || filename.isBlank() ? "document.docx" : filename;
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void deleteDirectoryQuietly(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
