package com.foodmap.deploy.uploader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ReleaseSplitter {
    static final int PART_SIZE = 4 * 1024 * 1024;
    private static final int MAX_PARTS = 10_000;

    private ReleaseSplitter() {}

    /** 复用固定大小缓冲区和现有发布脚本的四位编号，按文件名排序即可还原原包。 */
    static List<Path> split(Path archive, Path destination) throws IOException {
        if (!Files.isRegularFile(archive)) {
            throw new IllegalArgumentException("Release input must be a regular file.");
        }

        List<Path> parts = new ArrayList<>();
        byte[] buffer = new byte[PART_SIZE];
        Files.createDirectory(destination);
        try (InputStream input = Files.newInputStream(archive)) {
            int length;
            while ((length = input.readNBytes(buffer, 0, buffer.length)) > 0) {
                if (parts.size() == MAX_PARTS) {
                    throw new IOException("Release exceeds the four-digit part limit.");
                }
                Path part =
                        destination.resolve(String.format(Locale.ROOT, "release.part.%04d", parts.size()));
                try (OutputStream output =
                        Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    parts.add(part);
                    output.write(buffer, 0, length);
                }
            }
            if (parts.isEmpty()) {
                throw new IOException("Release file is empty.");
            }
        } catch (IOException exception) {
            // 只清理本次创建的分片。保留原始错误供命令行报告。
            for (Path part : parts) {
                deleteFailedOutput(part, exception);
            }
            deleteFailedOutput(destination, exception);
            throw exception;
        }
        return List.copyOf(parts);
    }

    private static void deleteFailedOutput(Path path, IOException originalFailure) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }
}
