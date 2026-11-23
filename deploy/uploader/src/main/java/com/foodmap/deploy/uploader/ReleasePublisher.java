package com.foodmap.deploy.uploader;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class ReleasePublisher {
    private static final Duration UPLOAD_BUDGET = Duration.ofMinutes(20);
    private static final Duration CONNECT_BUDGET = Duration.ofSeconds(45);
    private static final Duration ASSEMBLY_BUDGET = Duration.ofSeconds(90);
    private static final Duration ACTIVATION_BUDGET = Duration.ofMinutes(10);

    private ReleasePublisher() {}

    static void publish(Path workspace, Map<String, String> environment, PrintStream log)
            throws IOException, InterruptedException {
        var target = OpenSshClient.Target.from(environment);
        String key = credential(environment, "DEPLOY_SSH_KEY");
        String knownHosts = credential(environment, "DEPLOY_KNOWN_HOSTS");
        Path scratch = Files.createTempDirectory(
                "foodmap-uploader-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            var ssh = new OpenSshClient(target, writeCredential(scratch.resolve("key"), key),
                    writeCredential(scratch.resolve("known_hosts"), knownHosts), OpenSshClient::runProcess);
            publishFiles(workspace, scratch, target, ssh, log);
        } finally {
            // 优先删除凭据，即使分片目录随后发生清理错误。
            delete(scratch.resolve("key"), log);
            delete(scratch.resolve("known_hosts"), log);
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    delete(path, log);
                }
            } catch (IOException error) {
                log.println("Could not fully clean temporary upload files.");
            }
        }
    }

    static void publishFiles(Path workspace, Path scratch, OpenSshClient.Target target,
            OpenSshClient ssh, PrintStream log) throws IOException, InterruptedException {
        Path archive = workspace.resolve("release.tar.gz");
        Path checksum = workspace.resolve("SHA256SUMS");
        var metadata = List.of(checksum, workspace.resolve("deploy/deploy.sh"), workspace.resolve("deploy/healthcheck.py"));
        if (!Files.isRegularFile(archive) || metadata.stream().anyMatch(path -> !Files.isRegularFile(path))) {
            throw new IOException("Missing release archive, checksum, or deployment script.");
        }
        String digest = verifyChecksum(archive, checksum);
        var parts = ReleaseSplitter.split(archive, scratch.resolve("parts"));
        var cache = new ReleasePartCache(ssh, digest, target.incoming(), parts);
        ssh.checkPinnedHost(CONNECT_BUDGET);

        String incoming = "'" + target.incoming() + "'";
        long start = System.nanoTime();
        long deadline = start + UPLOAD_BUDGET.toNanos();
        var missing = cache.missingParts(CONNECT_BUDGET);
        log.printf("Resuming release: %d/%d verified remote parts reused, %d to upload.%n",
                parts.size() - missing.size(), parts.size(), missing.size());
        ChunkUploader.upload(missing, cache::upload, deadline, log);
        Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
        if (remaining.isNegative() || remaining.isZero()) {
            throw new IOException("Overall upload deadline reached before uploading metadata.");
        }
        Duration metadataTimeout = remaining.compareTo(CONNECT_BUDGET) < 0 ? remaining : CONNECT_BUDGET;
        OpenSshClient.requireSuccess(ssh.copy(metadata, metadataTimeout), "Upload release metadata");
        log.printf("Release upload completed in %d ms.%n", ChunkUploader.elapsedMillis(start));

        start = System.nanoTime();
        cache.assemble(ASSEMBLY_BUDGET);
        log.printf("Remote assembly and checksum completed in %d ms.%n", ChunkUploader.elapsedMillis(start));

        // 远端已经验证整包。激活只调用一次，连接中断时不能断定服务器是否已切换。
        start = System.nanoTime();
        ssh.ssh("cd " + incoming + " && bash './deploy.sh' '" + target.releaseId() + "'",
                ACTIVATION_BUDGET, "Activate release (check server version if the connection was interrupted)");
        log.printf("Release activation completed in %d ms.%n", ChunkUploader.elapsedMillis(start));
    }

    private static String verifyChecksum(Path archive, Path checksum) throws IOException {
        String line = Files.readString(checksum).stripTrailing();
        if (!line.matches("[0-9a-f]{64} [ *]release\\.tar\\.gz")) {
            throw new IOException("Invalid checksum file for release.tar.gz.");
        }
        String digest = sha256(archive);
        if (!digest.equals(line.substring(0, 64))) {
            throw new IOException("Local release checksum mismatch.");
        }
        return digest;
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Java runtime lacks SHA-256.", error);
        }
        try (var stream = new DigestInputStream(Files.newInputStream(file), digest)) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String credential(Map<String, String> environment, String name) {
        String value = environment.getOrDefault(name, "").replace("\r\n", "\n");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + name + ".");
        }
        return value.endsWith("\n") ? value : value + "\n";
    }

    private static Path writeCredential(Path file, String content) throws IOException {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.writeString(file, content);
    }

    private static void delete(Path file, PrintStream log) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException error) {
            log.println("Could not remove a temporary upload file.");
        }
    }
}
