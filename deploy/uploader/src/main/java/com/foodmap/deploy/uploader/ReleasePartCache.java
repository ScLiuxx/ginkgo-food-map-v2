package com.foodmap.deploy.uploader;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class ReleasePartCache {
    private static final String CACHE_ROOT = "/opt/foodmap/incoming/.part-cache";
    private static final Pattern HASH_LINE = Pattern.compile("([0-9a-f]{64}) [ *](release\\.part\\.[0-9]{4})");
    private static final Duration CACHE_WRITE_TIMEOUT = Duration.ofSeconds(45);

    private final OpenSshClient ssh;
    private final String directory;
    private final String incoming;
    private final Map<Path, String> localHashes = new LinkedHashMap<>();

    ReleasePartCache(OpenSshClient ssh, String releaseDigest, String incoming, List<Path> parts) throws IOException {
        this.ssh = ssh;
        this.directory = CACHE_ROOT + "/" + releaseDigest;
        this.incoming = incoming;
        for (Path part : parts) {
            localHashes.put(part, ReleasePublisher.sha256(part));
        }
    }

    List<Path> missingParts(Duration timeout) throws IOException, InterruptedException {
        var result = ssh.remote(
                "umask 077 && mkdir '" + incoming + "' && test ! -L '" + CACHE_ROOT
                        + "' && test ! -L '" + directory + "' && mkdir -p '" + directory + "' && cd '" + directory + "'"
                        + " && for part in release.part.[0-9][0-9][0-9][0-9]; do"
                        + " if test -f \"$part\" && test ! -L \"$part\"; then sha256sum --binary -- \"$part\" || exit 1; fi; done",
                timeout, OpenSshClient.Output.CAPTURE);
        OpenSshClient.requireSuccess(result, "Check remote part cache");
        var cachedHashes = new HashMap<String, String>();
        for (String line : result.stdout().lines().toList()) {
            var hash = HASH_LINE.matcher(line);
            if (!hash.matches() || cachedHashes.putIfAbsent(hash.group(2), hash.group(1)) != null) {
                throw new IOException("Invalid remote part cache inventory.");
            }
        }
        return localHashes.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(cachedHashes.get(entry.getKey().getFileName().toString())))
                .map(Map.Entry::getKey)
                .toList();
    }

    OpenSshClient.Result upload(Path part, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        var uploaded = ssh.copy(List.of(part), timeout);
        if (!uploaded.success()) {
            return uploaded;
        }
        Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
        if (remaining.isNegative() || remaining.isZero()) {
            return new OpenSshClient.Result(-1, true, "", "");
        }
        Duration commitTimeout = remaining.compareTo(CACHE_WRITE_TIMEOUT) < 0 ? remaining : CACHE_WRITE_TIMEOUT;
        String name = part.getFileName().toString();
        // SCP 先写本次尝试的目录。只把校验成功的分片原子移入共享缓存，避免暴露半片。
        return ssh.remote(
                "cd '" + incoming + "' && printf '%s *%s\\n' '" + localHashes.get(part) + "' '" + name + "'"
                        + " | sha256sum --check --status && mv -fT -- '" + name + "' '" + directory + "/" + name + "'",
                commitTimeout, OpenSshClient.Output.QUIET);
    }

    void assemble(Duration timeout) throws IOException, InterruptedException {
        // 只拼接当前发布包的连续编号，整包校验仍以本次上传的 SHA256SUMS 为准。
        ssh.ssh("cd '" + directory + "' && ("
                        + "i=0; while test \"$i\" -lt " + localHashes.size() + "; do"
                        + " part=$(printf 'release.part.%04d' \"$i\")"
                        + " && test ! -L \"$part\" && cat -- \"$part\" || exit 1; i=$((i + 1)); done"
                        + ") > '" + incoming + "/release.tar.gz'"
                        + " && cd '" + incoming + "' && sha256sum --check --status SHA256SUMS",
                timeout, "Assemble and verify remote release");
    }
}
