package com.commonbattle.cluster.process;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 独立 JVM 故障测试的进程控制器。
 * 只解析 CB_ 前缀控制帧，并保留有限日志尾部供超时诊断。
 */
final class JvmNodeProcess implements AutoCloseable {
    private static final int MAX_LOG_LINES = 200;

    private final Process process;
    private final BufferedWriter input;
    private final BlockingQueue<ProcessFrame> frames = new LinkedBlockingQueue<>();
    private final ArrayDeque<String> logTail = new ArrayDeque<>();
    private final Thread outputPump;

    private JvmNodeProcess(Process process) {
        this.process = process;
        this.input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.outputPump = Thread.ofPlatform()
                .daemon(true)
                .name("cluster-process-output-" + process.pid())
                .start(this::pumpOutput);
    }

    static JvmNodeProcess start(Class<?> mainClass, String... args) throws IOException {
        String testClasspath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path")
        );
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(testClasspath);
        command.add(mainClass.getName());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        return new JvmNodeProcess(builder.start());
    }

    void send(String command) throws IOException {
        input.write(command);
        input.newLine();
        input.flush();
    }

    ProcessFrame awaitResult(String requestId, Duration timeout) throws InterruptedException {
        return awaitForRequest("CB_RESULT", requestId, timeout);
    }

    ProcessFrame awaitForRequest(String type, String requestId, Duration timeout) throws InterruptedException {
        return await(frame -> type.equals(frame.field(0)) && requestId.equals(frame.field(1)), timeout);
    }

    ProcessFrame await(String type, Duration timeout) throws InterruptedException {
        return await(frame -> type.equals(frame.field(0)), timeout);
    }

    ProcessFrame await(FramePredicate predicate, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<ProcessFrame> unmatched = new ArrayList<>();
        try {
            while (System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();
                ProcessFrame frame = frames.poll(Math.max(1, remaining), TimeUnit.NANOSECONDS);
                if (frame == null) {
                    break;
                }
                if (predicate.matches(frame)) {
                    return frame;
                }
                unmatched.add(frame);
            }
            throw new AssertionError("timed out waiting for process frame; alive=" + alive()
                    + "\n" + logTail());
        } finally {
            unmatched.forEach(frames::offer);
        }
    }

    boolean alive() {
        return process.isAlive();
    }

    long pid() {
        return process.pid();
    }

    void crash() throws InterruptedException {
        process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("process did not stop\n" + logTail());
        }
    }

    String logTail() {
        synchronized (logTail) {
            return String.join(System.lineSeparator(), logTail);
        }
    }

    @Override
    public void close() {
        if (!process.isAlive()) {
            return;
        }
        try {
            send("EXIT");
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            process.destroyForcibly();
        }
    }

    private void pumpOutput() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                recordLog(line);
                if (line.startsWith("CB_")) {
                    frames.offer(new ProcessFrame(line, line.split("\\|", -1)));
                }
            }
        } catch (IOException error) {
            recordLog("output pump failed: " + error.getMessage());
        }
    }

    private void recordLog(String line) {
        synchronized (logTail) {
            if (logTail.size() == MAX_LOG_LINES) {
                logTail.removeFirst();
            }
            logTail.addLast(line);
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        Path candidate = Path.of(System.getProperty("java.home"), "bin", executable);
        return Files.isRegularFile(candidate) ? candidate.toString() : executable;
    }

    @FunctionalInterface
    interface FramePredicate {
        boolean matches(ProcessFrame frame);
    }
}

record ProcessFrame(String raw, String[] fields) {
    String field(int index) {
        if (index >= fields.length) {
            throw new AssertionError("missing field " + index + " in process frame: " + raw);
        }
        return fields[index];
    }
}
