package bdsm.integration.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class Stack {

    private static final long SCRIPT_TIMEOUT_MINUTES = 3;

    private Stack() {
    }

    public static void start(String service, Map<String, String> settings) throws Exception {
        script(settings, "scripts/start.sh", service);
    }

    public static void start(String service) throws Exception {
        start(service, Map.of());
    }

    public static void stop(String service) throws Exception {
        script(Map.of(), "scripts/stop.sh", service);
    }

    public static void feeder(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(javaCommand(), "-jar", Settings.FEEDER_JAR.toString()));
        command.addAll(List.of(arguments));
        run(command, Map.of());
    }

    public static long logLines(String service, String text) throws IOException {
        long matches = 0;
        try (var logs = Files.newDirectoryStream(Settings.LOG_DIR, service + "-*.log")) {
            for (Path log : logs) {
                try (var lines = Files.lines(log)) {
                    matches += lines.filter(line -> line.contains(text)).count();
                }
            }
        }
        return matches;
    }

    public static long logLinesOfReplica(String service, int replica, String text) throws IOException {
        try (var lines = Files.lines(Settings.LOG_DIR.resolve(service + "-" + replica + ".log"))) {
            return lines.filter(line -> line.contains(text)).count();
        }
    }

    private static void script(Map<String, String> settings, String... command) throws Exception {
        List<String> full = new ArrayList<>(List.of(Settings.STACK_BASH));
        full.addAll(List.of(command));
        run(full, settings);
    }

    private static void run(List<String> command, Map<String, String> settings) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(Settings.ROOT.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().putAll(settings);
        Process process = builder.start();
        if (!process.waitFor(SCRIPT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out: " + command);
        }
        if (process.exitValue() != 0) throw new AssertionError("Exit code " + process.exitValue() + ": " + command);
    }

    private static String javaCommand() {
        return ProcessHandle.current().info().command().orElse("java");
    }
}
