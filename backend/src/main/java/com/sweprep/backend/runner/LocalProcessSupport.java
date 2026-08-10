package com.sweprep.backend.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The subprocess machinery every local {@link Runner} needs - starting a process in a
 * work directory, capping captured stdout/stderr, honouring the timeout, and reading
 * back the requested output files - factored out so {@link LocalJavaRunner} and {@link
 * LocalPythonRunner} share it rather than each re-implementing the same process
 * bookkeeping. What differs between them (whether there is a separate compile step,
 * and how the run command is built) stays in each runner; only the parts that are
 * identical regardless of language live here.
 */
final class LocalProcessSupport {

    /** Upper bound on stdout/stderr kept in memory, so a runaway print loop cannot
     * exhaust the backend heap. */
    static final int MAX_CAPTURED_BYTES = 1024 * 1024;

    /** Upper bound on an output file read back into heap; see {@link #readOutputFiles}. */
    static final long MAX_RESULT_FILE_BYTES = 4L * 1024 * 1024;

    private LocalProcessSupport() {}

    static Path createWorkDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    static void writeFiles(Path dir, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(dir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Starts {@code command} in {@code workDir}, waits up to {@code request}'s timeout,
     * and reports the outcome - killing the process and returning {@link
     * ExecutionResult.Outcome#TIMEOUT} if it runs past that, otherwise reading back its
     * exit code, captured output, and requested output files.
     */
    static ExecutionResult run(Path workDir, List<String> command, ExecutionRequest request)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(workDir.toFile()).start();

        ExecutorService streams = Executors.newFixedThreadPool(2);
        Future<String> stdout = streams.submit(() -> drainCapped(process.getInputStream()));
        Future<String> stderr = streams.submit(() -> drainCapped(process.getErrorStream()));

        try {
            boolean finished = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor();
                return ExecutionResult.timeout(readQuietly(stdout), readQuietly(stderr));
            }
            OutputFiles outputs = readOutputFiles(workDir, request.outputFiles());
            return ExecutionResult.completed(
                    process.exitValue(),
                    readQuietly(stdout),
                    readQuietly(stderr),
                    outputs.present(),
                    outputs.oversized());
        } finally {
            streams.shutdownNow();
        }
    }

    /** The output files read back from the work directory, split by whether the
     * runner would load them into heap. */
    record OutputFiles(Map<String, String> present, List<String> oversized) {}

    /**
     * Reads back the files the program was asked to produce, from the work directory
     * before it is deleted. A file the program never wrote is simply omitted rather
     * than reported as empty, so a missing result is distinguishable from a
     * deliberately empty one. A file larger than {@link #MAX_RESULT_FILE_BYTES} is not
     * read into heap at all; its name is reported as oversized instead, kept distinct
     * from both absent and empty.
     */
    static OutputFiles readOutputFiles(Path workDir, List<String> names) throws IOException {
        Map<String, String> contents = new HashMap<>();
        List<String> oversized = new ArrayList<>();
        for (String name : names) {
            Path file = workDir.resolve(name);
            if (Files.isRegularFile(file)) {
                if (Files.size(file) > MAX_RESULT_FILE_BYTES) {
                    oversized.add(name);
                } else {
                    contents.put(name, Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
        return new OutputFiles(contents, oversized);
    }

    /**
     * Reads a stream fully - always draining so the process is never blocked by pipe
     * backpressure - but keeps at most {@link #MAX_CAPTURED_BYTES} in memory so a
     * runaway print loop cannot exhaust the backend heap.
     */
    private static String drainCapped(InputStream stream) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        boolean truncated = false;
        int read;
        while ((read = stream.read(chunk)) != -1) {
            int room = MAX_CAPTURED_BYTES - captured.size();
            if (room > 0) {
                captured.write(chunk, 0, Math.min(read, room));
            } else {
                truncated = true;
            }
        }
        String text = captured.toString(StandardCharsets.UTF_8);
        return truncated ? text + "\n...[output truncated]" : text;
    }

    private static String readQuietly(Future<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup of a temp directory
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of a temp directory
        }
    }
}
