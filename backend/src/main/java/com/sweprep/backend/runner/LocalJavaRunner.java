package com.sweprep.backend.runner;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * for why no sandbox is needed. The subprocess bookkeeping itself (starting the
 * process, capping captured output, reading back result files) is shared with
 * {@link LocalPythonRunner} via {@link LocalProcessSupport} - only the compile
 * step and the run command are Java-specific.
 */
@Component
public class LocalJavaRunner implements Runner {

    @Override
    public String languageId() {
        return "java";
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Path workDir = null;
        try {
            workDir = LocalProcessSupport.createWorkDir("sweprep-run-");
            LocalProcessSupport.writeFiles(workDir, request.sourceFiles());
            LocalProcessSupport.writeFiles(workDir, request.dataFiles());

            String compileError = compile(workDir, request.sourceFiles().keySet(), request.classpath());
            if (compileError != null) {
                return ExecutionResult.compileError(compileError);
            }
            return LocalProcessSupport.run(workDir, runCommand(workDir, request), request);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RunnerException("Local Java execution failed", e);
        } finally {
            LocalProcessSupport.deleteRecursively(workDir);
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

    private static List<String> runCommand(Path workDir, ExecutionRequest request) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(runClasspath(workDir, request.classpath()));
        command.add(request.mainClass());
        command.addAll(request.args());
        return command;
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

    /** Thrown when execution fails for reasons unrelated to the submission itself. */
    public static class RunnerException extends RuntimeException {
        public RunnerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
