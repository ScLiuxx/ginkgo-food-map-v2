package com.foodmap.deploy.uploader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/** GitHub 发布入口与本地分片命令。 */
public final class UploadMain {
    private static final String USAGE = """
            Usage: java -jar foodmap-uploader.jar split <release-file> <new-output-directory>
                   java -jar foodmap-uploader.jar publish <workspace-directory>  (Linux, GitHub Actions)
            """;

    private UploadMain() {}

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && "--help".equals(args[0])) {
            out.print(USAGE);
            return 0;
        }
        try {
            if (args.length == 3 && "split".equals(args[0])) {
                var parts = ReleaseSplitter.split(Path.of(args[1]), Path.of(args[2]));
                out.printf("Created %d release parts.%n", parts.size());
                return 0;
            }
            if (args.length == 2 && "publish".equals(args[0])) {
                if (!System.getProperty("os.name").equals("Linux")) {
                    err.println("Publishing requires a Linux runner.");
                    return 2;
                }
                ReleasePublisher.publish(Path.of(args[1]), System.getenv(), out);
                return 0;
            }
            err.print(USAGE);
            return 2;
        } catch (IllegalArgumentException exception) {
            err.println(exception.getMessage());
            return 2;
        } catch (IOException exception) {
            err.println(exception.getMessage());
            return 1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            err.println("Release upload interrupted.");
            return 130;
        }
    }
}
