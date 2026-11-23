package com.foodmap.deploy.uploader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class ChunkUploader {
    static final int PARALLELISM = 8;
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration PART_TIMEOUT = Duration.ofMinutes(7);

    private ChunkUploader() {}

    static void upload(List<Path> parts, PartTransfer transfer, long deadline, PrintStream log)
            throws IOException, InterruptedException {
        log.printf("Uploading %d release parts, up to %d connections.%n", parts.size(), PARALLELISM);
        try (var executor = Executors.newFixedThreadPool(PARALLELISM)) {
            var completed = new ExecutorCompletionService<Void>(executor);
            var remaining = parts.iterator();
            try {
                for (int i = 0; i < PARALLELISM && remaining.hasNext(); i++) {
                    submit(completed, remaining.next(), transfer, log);
                }
                for (int uploaded = 0; uploaded < parts.size(); uploaded++) {
                    var next = completed.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    if (next == null) {
                        throw new IOException("Overall upload deadline reached.");
                    }
                    next.get();
                    if (remaining.hasNext()) {
                        submit(completed, remaining.next(), transfer, log);
                    }
                }
            } catch (ExecutionException error) {
                throw new IOException(error.getCause().getMessage(), error.getCause());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void submit(
            ExecutorCompletionService<Void> completed, Path part, PartTransfer transfer, PrintStream log) {
        completed.submit(() -> {
            uploadPart(part, transfer, log);
            return null;
        });
    }

    private static void uploadPart(Path part, PartTransfer transfer, PrintStream log)
            throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long start = System.nanoTime();
            var result = transfer.upload(part, PART_TIMEOUT);
            log.printf("%s attempt %d/%d: %s in %d ms%n", part.getFileName(), attempt, MAX_ATTEMPTS,
                    result.success() ? "uploaded" : result.description(), elapsedMillis(start));
            if (result.success()) {
                return;
            }
            if (!result.retryable() || attempt == MAX_ATTEMPTS) {
                throw new IOException("Upload failed for " + part.getFileName() + " (" + result.description() + ").");
            }
            Thread.sleep(Duration.ofSeconds(1));
        }
    }

    static long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @FunctionalInterface
    interface PartTransfer {
        OpenSshClient.Result upload(Path part, Duration timeout) throws IOException, InterruptedException;
    }
}
