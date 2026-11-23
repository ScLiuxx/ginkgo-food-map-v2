package com.foodmap.deploy.uploader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReleasePublisherTest {
    private static final OpenSshClient.Result OK = new OpenSshClient.Result(0, false, "", "");
    private static final OpenSshClient.Result DROPPED =
            new OpenSshClient.Result(1, false, "client_loop: send disconnect: Broken pipe", "");
    private static final OpenSshClient.Result DENIED =
            new OpenSshClient.Result(1, false, "Permission denied (publickey). Connection closed", "");
    private static final Map<String, String> ENV = Map.of(
            "DEPLOY_HOST", "example.test", "DEPLOY_USER", "foodmap-deploy", "DEPLOY_PORT", "02222",
            "GITHUB_SHA", "a".repeat(40), "GITHUB_RUN_ID", "100", "GITHUB_RUN_ATTEMPT", "2");

    @TempDir Path directory;

    private final List<List<String>> calls = new CopyOnWriteArrayList<>();
    private Path scratch;

    @BeforeEach
    void prepareRelease() throws Exception {
        Files.createDirectory(directory.resolve("deploy"));
        Files.writeString(directory.resolve("deploy/deploy.sh"), "# fake deploy");
        Files.writeString(directory.resolve("deploy/healthcheck.py"), "# fake check");
        byte[] release = new byte[ReleaseSplitter.PART_SIZE + 1];
        Files.write(directory.resolve("release.tar.gz"), release);
        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(release);
        Files.writeString(directory.resolve("SHA256SUMS"), HexFormat.of().formatHex(checksum) + "  release.tar.gz\n");
        scratch = Files.createDirectory(directory.resolve("scratch"));
    }

    @Test
    void retriesOnlyFailedPartThenAssemblesAndActivatesOnce() throws Exception {
        var firstAttempts = new AtomicInteger();
        publish(command -> {
            if (isPart(command, "0000") && firstAttempts.getAndIncrement() == 0) {
                return DROPPED;
            }
            return OK;
        });

        assertEquals(2, partAttempts("0000"));
        assertEquals(1, partAttempts("0001"));
        assertTrue(calls.getFirst().contains("[example.test]:2222"));
        assertTrue(calls.get(1).contains("StrictHostKeyChecking=yes"));
        assertTrue(calls.get(1).getLast().contains("-100-2"));
        assertTrue(calls.get(calls.size() - 3).stream().anyMatch(value -> value.endsWith("healthcheck.py")));
        assertTrue(calls.get(calls.size() - 2).getLast().contains("sha256sum --check --status SHA256SUMS"));
        assertTrue(calls.getLast().getLast().contains("bash './deploy.sh'"));
        assertEquals(1, count("bash './deploy.sh'"));
    }

    @Test
    void neverSendsMetadataOrActivatesWhenPartRunsOutOfRetries() {
        assertThrows(IOException.class, () -> publish(command -> isPart(command, "0000") ? DROPPED : OK));

        assertEquals(3, partAttempts("0000"));
        assertEquals(0, count("healthcheck.py"));
        assertEquals(0, count("bash './deploy.sh'"));
    }

    @Test
    void doesNotRetryAuthenticationFailure() {
        assertThrows(IOException.class, () -> publish(command -> isPart(command, "0000") ? DENIED : OK));
        assertEquals(1, partAttempts("0000"));
        assertEquals(0, count("bash './deploy.sh'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"healthcheck.py", "sha256sum --check --status SHA256SUMS"})
    void blocksActivationWhenMetadataOrAssemblyFails(String failedCommand) {
        assertThrows(IOException.class, () -> publish(command -> hasText(command, failedCommand) ? DENIED : OK));
        assertEquals(1, count(failedCommand));
        assertEquals(0, count("bash './deploy.sh'"));
    }

    @Test
    void attemptsActivationOnlyOnceEvenWhenTheConnectionIsLost() {
        assertThrows(IOException.class, () -> publish(command -> hasText(command, "bash './deploy.sh'") ? DROPPED : OK));
        assertEquals(1, count("bash './deploy.sh'"));
    }

    @Test
    void rejectsCorruptedReleaseBeforeStartingAnyProcess() throws Exception {
        Files.writeString(directory.resolve("release.tar.gz"), "corrupted");
        assertThrows(IOException.class, () -> publish(command -> OK));
        assertTrue(calls.isEmpty());
    }

    @Test
    void stopsBeforeConnectingWhenTheHostHasNoPinnedKey() {
        assertThrows(IOException.class, () -> publish(command -> DENIED));
        assertEquals(1, calls.size());
        assertEquals("ssh-keygen", calls.getFirst().getFirst());
    }

    @Test
    void reusesVerifiedPartFromAnEarlierFailedAttempt() throws Exception {
        var cached = new CountDownLatch(1);
        assertThrows(IOException.class, () -> publish(command -> {
            if (isPart(command, "0001")) {
                assertTrue(cached.await(5, TimeUnit.SECONDS));
                return DENIED;
            }
            if (hasText(command, "mv -fT -- 'release.part.0000'")) {
                cached.countDown();
            }
            return OK;
        }));
        assertEquals(0, count("bash './deploy.sh'"));
        String verifiedPart = ReleasePublisher.sha256(scratch.resolve("parts/release.part.0000"));
        String cachePath = "/opt/foodmap/incoming/.part-cache/"
                + ReleasePublisher.sha256(directory.resolve("release.tar.gz"));
        assertTrue(count(cachePath) > 0);

        calls.clear();
        scratch = Files.createDirectory(directory.resolve("retry-scratch"));
        publish("3", command -> hasText(command, "for part in release.part.") ? inventory(
                verifiedPart + "  release.part.0000\n") : OK);
        assertEquals(0, partAttempts("0000"));
        assertEquals(1, partAttempts("0001"));
        assertTrue(calls.get(1).getLast().contains(cachePath));
        assertTrue(calls.get(1).getLast().contains("-100-3"));
        assertEquals(1, count("bash './deploy.sh'"));
    }

    @Test
    void replacesCorruptedPartAndUploadsMissingPart() throws Exception {
        Path release = directory.resolve("release.tar.gz");
        Files.write(release, new byte[ReleaseSplitter.PART_SIZE], StandardOpenOption.APPEND);
        Files.writeString(directory.resolve("SHA256SUMS"), ReleasePublisher.sha256(release) + "  release.tar.gz\n");
        String correct = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(new byte[ReleaseSplitter.PART_SIZE]));

        publish(command -> hasText(command, "for part in release.part.") ? inventory(
                correct + "  release.part.0000\n" + "0".repeat(64) + "  release.part.0001\n") : OK);
        assertEquals(0, partAttempts("0000"));
        assertEquals(1, partAttempts("0001"));
        assertEquals(1, partAttempts("0002"));
        assertEquals(1, count("bash './deploy.sh'"));
    }

    @Test
    void rejectsInvalidInventoryBeforeUploadingOrActivating() {
        assertThrows(IOException.class, () -> publish(command -> hasText(command, "for part in release.part.")
                ? inventory("Unexpected inventory output\n") : OK));
        assertEquals(0, count("healthcheck.py"));
        assertEquals(0, count("bash './deploy.sh'"));
    }

    @Test
    void resumesThroughTheGeneratedShellCommands() throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path bash = windows
                ? Path.of(System.getenv().getOrDefault("ProgramFiles", "C:/Program Files"), "Git", "bin", "bash.exe")
                : Path.of("/bin/bash");
        assumeTrue(Files.isRegularFile(bash), "Bash is required for simulated server checks");
        Path remote = Files.createDirectory(directory.resolve("remote incoming"));
        Files.writeString(directory.resolve("deploy/deploy.sh"),
                "test -f release.tar.gz && printf '%s\\n' \"$1\" > activated\n");
        var cached = new CountDownLatch(1);
        assertThrows(IOException.class, () -> publish(command -> {
            if (isPart(command, "0001")) {
                assertTrue(cached.await(5, TimeUnit.SECONDS));
                return DENIED;
            }
            var result = simulateServer(command, bash, remote);
            if (hasText(command, "mv -fT -- 'release.part.0000'") && result.success()) {
                cached.countDown();
            }
            return result;
        }));

        assertEquals(0, count("bash './deploy.sh'"));
        calls.clear();
        scratch = Files.createDirectory(directory.resolve("retry-scratch"));
        publish("3", command -> simulateServer(command, bash, remote));
        assertEquals(0, partAttempts("0000"));
        assertEquals(1, partAttempts("0001"));
        Path activated = remote.resolve("a".repeat(40) + "-100-3");
        assertEquals(-1, Files.mismatch(directory.resolve("release.tar.gz"), activated.resolve("release.tar.gz")));
        assertEquals(activated.getFileName().toString(), Files.readString(activated.resolve("activated")).strip());
    }

    private OpenSshClient.Result simulateServer(List<String> command, Path bash, Path remote)
            throws IOException, InterruptedException {
        String commandName = command.getFirst();
        String incoming = "/opt/foodmap/incoming";
        if (commandName.equals("scp")) {
            String destination = command.getLast();
            Path attempt = remote.resolve(destination.substring(destination.indexOf(incoming) + incoming.length() + 1));
            int start = command.indexOf("-P") + 2;
            for (String argument : command.subList(start, command.size() - 1)) {
                Path part = Path.of(argument);
                Files.copy(part, attempt.resolve(part.getFileName()));
            }
            return OK;
        }
        if (commandName.equals("ssh")) {
            Path script = Files.createTempFile(directory, "server-", ".sh");
            Files.writeString(script, command.getLast().replace(incoming, bashPath(remote).replace("'", "'\\''")));
            var result = OpenSshClient.runProcess(
                    List.of(bash.toString(), bashPath(script)),
                    Duration.ofSeconds(10), OpenSshClient.Output.CAPTURE);
            assertTrue(result.success(), result.diagnostic());
            return result;
        }
        assertEquals("ssh-keygen", commandName);
        return OK;
    }

    private static String bashPath(Path path) {
        String absolute = path.toAbsolutePath().toString().replace('\\', '/');
        if (absolute.charAt(1) == ':') {
            return "/" + absolute.substring(0, 1).toLowerCase(Locale.ROOT) + absolute.substring(2);
        }
        return absolute;
    }

    @ParameterizedTest
    @ValueSource(strings = {"../release", "12;echo"})
    void rejectsInvalidRunIds(String runId) {
        var environment = new HashMap<>(ENV);
        environment.put("GITHUB_RUN_ID", runId);
        assertThrows(IllegalArgumentException.class, () -> OpenSshClient.Target.from(environment));
    }

    private void publish(CommandResult handler) throws IOException, InterruptedException {
        publish("2", handler);
    }

    private void publish(String attempt, CommandResult handler) throws IOException, InterruptedException {
        var environment = new HashMap<>(ENV);
        environment.put("GITHUB_RUN_ATTEMPT", attempt);
        var target = OpenSshClient.Target.from(environment);
        var ssh = new OpenSshClient(target, scratch.resolve("key"), scratch.resolve("known_hosts"),
                (command, timeout, showOutput) -> {
                    assertFalse(timeout.isNegative() || timeout.isZero());
                    calls.add(List.copyOf(command));
                    return handler.run(command);
                });
        try (var log = new PrintStream(OutputStream.nullOutputStream())) {
            ReleasePublisher.publishFiles(directory, scratch, target, ssh, log);
        }
    }

    private static OpenSshClient.Result inventory(String stdout) {
        return new OpenSshClient.Result(0, false, "", stdout);
    }

    private long partAttempts(String suffix) {
        return calls.stream().filter(command -> isPart(command, suffix)).count();
    }

    private long count(String text) {
        return calls.stream().filter(command -> hasText(command, text)).count();
    }

    private static boolean isPart(List<String> command, String suffix) {
        return command.getFirst().equals("scp") && hasText(command, "release.part." + suffix);
    }

    private static boolean hasText(List<String> command, String text) {
        return command.stream().anyMatch(argument -> argument.contains(text));
    }

    @FunctionalInterface
    private interface CommandResult {
        OpenSshClient.Result run(List<String> command) throws IOException, InterruptedException;
    }
}
