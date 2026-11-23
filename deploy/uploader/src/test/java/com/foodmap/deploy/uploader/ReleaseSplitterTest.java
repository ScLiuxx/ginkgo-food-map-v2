package com.foodmap.deploy.uploader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseSplitterTest {
    @TempDir Path directory;

    @Test
    void splitsAtFourMiBAndReassemblesTheOriginalBytes() throws IOException {
        Path archive = directory.resolve("release.tar.gz");
        byte[] data = new byte[4 * 1024 * 1024 + 3];
        data[data.length - 4] = 42;
        data[data.length - 3] = 73;
        data[data.length - 1] = -1;
        Files.write(archive, data);

        Path destination = directory.resolve("parts");
        List<Path> parts = ReleaseSplitter.split(archive, destination);

        assertEquals(
                List.of(destination.resolve("release.part.0000"), destination.resolve("release.part.0001")),
                parts);
        assertEquals(4L * 1024 * 1024, Files.size(parts.getFirst()));
        assertEquals(3L, Files.size(parts.getLast()));
        Path reassembled = directory.resolve("reassembled.tar.gz");
        try (OutputStream output = Files.newOutputStream(reassembled)) {
            for (Path part : parts) {
                Files.copy(part, output);
            }
        }
        assertEquals(-1, Files.mismatch(archive, reassembled));
    }

    @Test
    void preservesAnExistingOutputDirectory() throws IOException {
        Path archive = Files.writeString(directory.resolve("release.tar.gz"), "release");
        Path destination = Files.createDirectory(directory.resolve("parts"));
        Path existingPart = Files.writeString(destination.resolve("release.part.0000"), "original");

        assertThrows(FileAlreadyExistsException.class, () -> ReleaseSplitter.split(archive, destination));
        assertEquals("original", Files.readString(existingPart));
    }

    @Test
    void leavesNoOutputForAnEmptyRelease() throws IOException {
        Path archive = Files.createFile(directory.resolve("release.tar.gz"));
        Path destination = directory.resolve("parts");

        assertThrows(IOException.class, () -> ReleaseSplitter.split(archive, destination));
        assertFalse(Files.exists(destination));
    }
}
