package com.foodmap.deploy.uploader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class OpenSshClient {
    private final Target target;
    private final Path knownHosts;
    private final List<String> options;
    private final CommandRunner runner;

    OpenSshClient(Target target, Path key, Path knownHosts, CommandRunner runner) {
        this.target = target;
        this.knownHosts = knownHosts;
        this.runner = runner;
        options = List.of(
                "-F", "/dev/null", "-i", key.toString(),
                "-o", "BatchMode=yes",
                "-o", "IdentitiesOnly=yes",
                "-o", "StrictHostKeyChecking=yes",
                "-o", "UserKnownHostsFile=" + knownHosts,
                "-o", "GlobalKnownHostsFile=/dev/null",
                "-o", "ConnectTimeout=15",
                "-o", "ConnectionAttempts=1",
                "-o", "ServerAliveInterval=15",
                "-o", "ServerAliveCountMax=4");
    }

    void checkPinnedHost(Duration timeout) throws IOException, InterruptedException {
        var command = List.of("ssh-keygen", "-F", target.knownHost(), "-f", knownHosts.toString());
        requireSuccess(runner.run(command, timeout, Output.QUIET), "Pinned host key lookup");
    }

    void ssh(String remoteCommand, Duration timeout, String step) throws IOException, InterruptedException {
        requireSuccess(remote(remoteCommand, timeout, Output.INHERIT), step);
    }

    Result remote(String remoteCommand, Duration timeout, Output output) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add("ssh");
        command.addAll(options);
        command.addAll(List.of("-p", target.port(), target.address(), remoteCommand));
        return runner.run(command, timeout, output);
    }

    Result copy(List<Path> files, Duration timeout) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add("scp");
        command.addAll(options);
        command.addAll(List.of("-P", target.port()));
        for (Path file : files) {
            command.add(file.toAbsolutePath().toString());
        }
        command.add(target.address() + ":" + target.incoming() + "/");
        return runner.run(command, timeout, Output.QUIET);
    }

    static void requireSuccess(Result result, String step) throws IOException {
        if (!result.success()) {
            throw new IOException(step + " failed (" + result.description() + ").");
        }
    }

    /** SCP 的退出码覆盖认证、磁盘和网络故障，只对明确的临时连接问题重试。 */
    record Result(int exitCode, boolean timedOut, String diagnostic, String stdout) {
        boolean success() {
            return !timedOut && exitCode == 0;
        }

        boolean retryable() {
            if (timedOut) {
                return true;
            }
            if (exitCode == 0) {
                return false;
            }
            String text = diagnostic.toLowerCase(Locale.ROOT);
            if (text.contains("permission denied") || text.contains("host key verification failed")
                    || text.contains("remote host identification has changed") || text.contains("no space left")) {
                return false;
            }
            return text.contains("connection timed out")
                    || text.contains("connection reset")
                    || text.contains("connection closed")
                    || text.contains("connection refused")
                    || text.contains("broken pipe")
                    || text.contains("lost connection");
        }

        String description() {
            return timedOut ? "timeout" : "exit " + exitCode;
        }
    }

    enum Output {
        INHERIT, QUIET, CAPTURE
    }

    record Target(String host, String port, String releaseId) {
        static Target from(Map<String, String> environment) {
            String host = environment.getOrDefault("DEPLOY_HOST", "");
            if (!host.matches("[a-zA-Z0-9][a-zA-Z0-9.-]*")) {
                throw new IllegalArgumentException("Invalid DEPLOY_HOST.");
            }
            if (!"foodmap-deploy".equals(environment.get("DEPLOY_USER"))) {
                throw new IllegalArgumentException("DEPLOY_USER must be foodmap-deploy.");
            }
            String port = environment.getOrDefault("DEPLOY_PORT", "");
            if (port.isEmpty()) {
                port = "22";
            }
            if (!port.matches("[0-9]{1,5}")) {
                throw new IllegalArgumentException("Invalid DEPLOY_PORT.");
            }
            int parsedPort = Integer.parseInt(port);
            if (parsedPort < 1 || parsedPort > 65535) {
                throw new IllegalArgumentException("Invalid DEPLOY_PORT.");
            }
            String releaseId = environment.get("GITHUB_SHA") + "-" + environment.get("GITHUB_RUN_ID")
                    + "-" + environment.get("GITHUB_RUN_ATTEMPT");
            if (!releaseId.matches("[a-f0-9]{40}-[0-9]+-[0-9]+")) {
                throw new IllegalArgumentException("Invalid release ID.");
            }
            return new Target(host, Integer.toString(parsedPort), releaseId);
        }

        String knownHost() {
            return port.equals("22") ? host : "[" + host + "]:" + port;
        }

        String address() {
            return "foodmap-deploy@" + host;
        }

        String incoming() {
            return "/opt/foodmap/incoming/" + releaseId;
        }
    }

    @FunctionalInterface
    interface CommandRunner {
        Result run(List<String> command, Duration timeout, Output output)
                throws IOException, InterruptedException;
    }

    static Result runProcess(List<String> command, Duration timeout, Output output)
            throws IOException, InterruptedException {
        var builder = new ProcessBuilder(command);
        builder.environment().remove("DEPLOY_SSH_KEY");
        builder.environment().remove("DEPLOY_KNOWN_HOSTS");
        builder.environment().put("LC_ALL", "C");
        builder.redirectOutput(switch (output) {
            case INHERIT -> ProcessBuilder.Redirect.INHERIT;
            case QUIET -> ProcessBuilder.Redirect.DISCARD;
            case CAPTURE -> ProcessBuilder.Redirect.PIPE;
        });
        builder.redirectError(output == Output.INHERIT ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            var diagnostic = output == Output.INHERIT ? null : drain(process.getErrorStream(), 8192);
            // 10000 个四位分片的 sha256sum 清单小于 1 MiB。
            var stdout = output == Output.CAPTURE ? drain(process.getInputStream(), 1024 * 1024 + 1) : null;
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return new Result(-1, true, "", "");
            }
            String text = diagnostic == null ? "" : diagnostic.get(5, TimeUnit.SECONDS);
            String captured = stdout == null ? "" : stdout.get(5, TimeUnit.SECONDS);
            if (captured.length() > 1024 * 1024) {
                throw new IOException("OpenSSH output exceeded the inventory limit.");
            }
            return new Result(process.exitValue(), false, text, captured);
        } catch (ExecutionException | TimeoutException error) {
            throw new IOException("Could not read OpenSSH status.", error);
        } finally {
            if (process.isAlive()) {
                // SCP 还会启动 SSH 子进程，取消上传时一并终止。
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                if (!process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("Could not terminate a timed-out OpenSSH process.");
                }
            }
        }
    }

    private static FutureTask<String> drain(InputStream stream, int limit) {
        // 持续排空管道，避免子进程被阻塞，每路只保留指定上限的字节。
        var task = new FutureTask<>(() -> {
            var captured = new ByteArrayOutputStream(4096);
            try (stream) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = stream.read(buffer)) != -1) {
                    captured.write(buffer, 0, Math.min(length, limit - captured.size()));
                }
            }
            return captured.toString(StandardCharsets.UTF_8);
        });
        Thread.ofPlatform().daemon().name("foodmap-openssh-output").start(task);
        return task;
    }
}
