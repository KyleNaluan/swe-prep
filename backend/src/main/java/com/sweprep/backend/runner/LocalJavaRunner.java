package com.sweprep.backend.runner;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.springframework.stereotype.Component;

/**
 * A {@link Runner} that compiles and runs Java in a local subprocess with no
 * sandbox. Compilation uses the in-process JDK compiler, so its diagnostics can
 * be reported precisely; the program itself runs in a forked JVM so a timeout can
 * kill it outright, which is how an infinite loop is caught. See {@link Runner}
 * for why no sandbox is needed.
 */
@Component
public class LocalJavaRunner implements Runner {

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("sweprep-run-");
            writeFiles(workDir, request.sourceFiles());
            writeFiles(workDir, request.dataFiles());

            String compileError = compile(workDir, request.sourceFiles().keySet(), request.classpath());
            if (compileError != null) {
                return ExecutionResult.compileError(compileError);
            }
            return run(workDir, request);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RunnerException("Local Java execution failed", e);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static void writeFiles(Path dir, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(dir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** Returns compiler diagnostics on failure, or {@code null} on success. */
    private static String compile(Path workDir, Iterable<String> sourceNames, List<String> classpath)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RunnerException(
                    "No system Java compiler available; the backend must run on a JDK, not a JRE", null);
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {

            List<Path> sourcePaths = new ArrayList<>();
            for (String name : sourceNames) {
                sourcePaths.add(workDir.resolve(name));
            }
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromPaths(sourcePaths);

            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(workDir));
            if (!classpath.isEmpty()) {
                fileManager.setLocationFromPaths(
                        StandardLocation.CLASS_PATH, classpath.stream().map(Path::of).toList());
            }

            StringWriter compilerOutput = new StringWriter();
            boolean ok = compiler
                    .getTask(compilerOutput, fileManager, diagnostics, null, null, units)
                    .call();
            if (ok) {
                return null;
            }
            return formatDiagnostics(diagnostics, compilerOutput.toString());
        }
    }

    private static String formatDiagnostics(
            DiagnosticCollector<JavaFileObject> diagnostics, String extraOutput) {
        StringBuilder message = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            JavaFileObject source = diagnostic.getSource();
            if (source != null && diagnostic.getLineNumber() != Diagnostic.NOPOS) {
                String fileName = Path.of(source.getName()).getFileName().toString();
                message.append(fileName)
                        .append(':')
                        .append(diagnostic.getLineNumber())
                        .append(": ");
            }
            message.append(diagnostic.getMessage(Locale.ROOT)).append('\n');
        }
        if (message.isEmpty()) {
            message.append(extraOutput.isBlank() ? "Compilation failed" : extraOutput);
        }
        return message.toString().strip();
    }

    private static ExecutionResult run(Path workDir, ExecutionRequest request)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(runClasspath(workDir, request.classpath()));
        command.add(request.mainClass());
        command.addAll(request.args());

        Process process = new ProcessBuilder(command).directory(workDir.toFile()).start();

        ExecutorService streams = Executors.newFixedThreadPool(2);
        Future<String> stdout = streams.submit(() ->
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        Future<String> stderr = streams.submit(() ->
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));

        try {
            boolean finished = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor();
                return ExecutionResult.timeout(readQuietly(stdout), readQuietly(stderr));
            }
            return ExecutionResult.completed(
                    process.exitValue(), readQuietly(stdout), readQuietly(stderr));
        } finally {
            streams.shutdownNow();
        }
    }

    private static String runClasspath(Path workDir, List<String> classpath) {
        List<String> entries = new ArrayList<>();
        entries.add(workDir.toString());
        entries.addAll(classpath);
        return String.join(java.io.File.pathSeparator, entries);
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        String binary = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return Path.of(home, "bin", binary).toString();
    }

    private static String readQuietly(Future<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private static void deleteRecursively(Path dir) {
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

    /** Thrown when execution fails for reasons unrelated to the submission itself. */
    public static class RunnerException extends RuntimeException {
        public RunnerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
