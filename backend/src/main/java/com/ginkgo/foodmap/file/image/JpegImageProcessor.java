package com.ginkgo.foodmap.file.image;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.exception.FileErrorCode;

import org.springframework.stereotype.Component;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** 首版接受前端压缩后的JPEG；解码前检查尺寸，重新编码去除EXIF和附加内容。 */
@Component
public class JpegImageProcessor {
    public static final int MAX_BYTES = 300 * 1024;

    /** 即使调用方伪造MIME/扩展名，也只根据文件内容和解码器结果判断。 */
    public ProcessedImage process(InputStream source) {
        try {
            byte[] input = source.readNBytes(MAX_BYTES + 1);
            if (input.length == 0 || input.length > MAX_BYTES) {
                throw invalid();
            }
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) {
                    throw invalid();
                }
                var reader = readers.next();
                try {
                    if (!"JPEG".equalsIgnoreCase(reader.getFormatName())) {
                        throw invalid();
                    }
                    reader.setInput(stream, true, true);
                    var warned = new java.util.concurrent.atomic.AtomicBoolean();
                    reader.addIIOReadWarningListener((ignored, warning) -> warned.set(true));
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 1200 || height > 1200) {
                        throw invalid();
                    }
                    var decoded = reader.read(0);
                    if (warned.get() || decoded == null) {
                        throw invalid();
                    }
                    // 截断文件可能被解码器宽容接受；出现警告时拒绝，避免保存损坏图片。
                    var output = new ByteArrayOutputStream();
                    if (!ImageIO.write(decoded, "jpeg", output)) {
                        throw invalid();
                    }
                    byte[] bytes = output.toByteArray();
                    if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                        throw invalid();
                    }
                    var result = new ProcessedImage();
                    result.setBytes(bytes);
                    result.setWidth(width);
                    result.setHeight(height);
                    result.setSha256(
                            HexFormat.of()
                                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    return result;
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw invalid();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private BusinessException invalid() {
        return new BusinessException(FileErrorCode.INVALID_IMAGE);
    }
}
