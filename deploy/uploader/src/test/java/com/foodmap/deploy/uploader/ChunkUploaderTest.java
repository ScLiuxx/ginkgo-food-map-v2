package com.foodmap.deploy.uploader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChunkUploaderTest {
    @Test
    void startsNinthPartWhileTheFirstIsStillUploading() throws Exception {
        var firstEight = new CountDownLatch(8);
        var ninthStarted = new CountDownLatch(1);
        var firstFinished = new AtomicBoolean();
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var parts = new ArrayList<Path>();
        for (int index = 0; index < 9; index++) {
            parts.add(Path.of("part-" + index));
        }
        ChunkUploader.PartTransfer transfer = (file, timeout) -> {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            String part = file.getFileName().toString();
            try {
                if (part.equals("part-8")) {
                    assertFalse(firstFinished.get());
                    ninthStarted.countDown();
                } else {
                    firstEight.countDown();
                    assertTrue(firstEight.await(5, TimeUnit.SECONDS));
                    if (part.equals("part-0")) {
                        assertTrue(ninthStarted.await(5, TimeUnit.SECONDS));
                        firstFinished.set(true);
                    }
                }
                return new OpenSshClient.Result(0, false, "", "");
            } finally {
                active.decrementAndGet();
            }
        };
        try (var log = new PrintStream(OutputStream.nullOutputStream())) {
            ChunkUploader.upload(parts, transfer, System.nanoTime() + Duration.ofSeconds(10).toNanos(), log);
        }
        assertEquals(8, peak.get());
        assertTrue(firstFinished.get());
    }

    @Test
    void uploadDeadlineInterruptsActiveTasks() throws Exception {
        var interrupted = new CountDownLatch(1);
        ChunkUploader.PartTransfer transfer = (part, timeout) -> {
            try {
                Thread.sleep(Duration.ofMinutes(1));
                return new OpenSshClient.Result(0, false, "", "");
            } catch (InterruptedException error) {
                interrupted.countDown();
                throw error;
            }
        };
        try (var log = new PrintStream(OutputStream.nullOutputStream())) {
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            assertThrows(IOException.class, () -> ChunkUploader.upload(
                    List.of(Path.of("part-0")), transfer, deadline, log));
        }
        assertEquals(0, interrupted.getCount());
    }
}
