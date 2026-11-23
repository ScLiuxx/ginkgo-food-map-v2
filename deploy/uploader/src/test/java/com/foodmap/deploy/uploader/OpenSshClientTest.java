package com.foodmap.deploy.uploader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenSshClientTest {
    @Test
    void capturesConnectionErrorsWithoutWaitingOnAFullPipe() throws Exception {
        var result = OpenSshClient.runProcess(command("error"), Duration.ofSeconds(10), OpenSshClient.Output.QUIET);
        assertEquals(1, result.exitCode());
        assertTrue(result.retryable());
    }

    @Test
    void terminatesProcessWhenTheAttemptTimesOut() throws Exception {
        var result = OpenSshClient.runProcess(command("wait"), Duration.ofSeconds(1), OpenSshClient.Output.QUIET);
        assertTrue(result.timedOut());
    }

    @Test
    void capturesPartInventoryFromStdout() throws Exception {
        var result = OpenSshClient.runProcess(
                command("inventory"), Duration.ofSeconds(10), OpenSshClient.Output.CAPTURE);
        assertTrue(result.success());
        assertEquals("a".repeat(64) + "  release.part.0000", result.stdout().strip());
    }

    private static List<String> command(String argument) {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        return List.of(java.toString(), "-Xmx32m", "-cp", System.getProperty("java.class.path"),
                FakeOpenSsh.class.getName(), argument);
    }

    public static class FakeOpenSsh {
        public static void main(String[] args) throws InterruptedException {
            if ("inventory".equals(args[0])) {
                System.out.println("a".repeat(64) + "  release.part.0000");
                return;
            }
            if ("error".equals(args[0])) {
                System.err.println("Broken pipe");
                System.err.print("x".repeat(128 * 1024));
                System.exit(1);
            }
            Thread.sleep(Duration.ofMinutes(1));
        }
    }
}
