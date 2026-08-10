package com.sweprep.backend.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A {@link Runner} that runs Python in a local subprocess with no sandbox - the second
 * language runner (issue #26), proving the same execution seam Java's {@link
 * LocalJavaRunner} implements also carries an interpreted language with no separate
 * compilation model. Requires a {@code python3} (or {@link #pythonExecutable}) on
 * {@code PATH}; no third-party packages are needed since the generated harness only
 * imports the standard library.
 *
 * <p>Python has no compile-then-run split the way Java does, but a syntax error still
 * needs to surface as {@link ExecutionResult.Outcome#COMPILE_ERROR} rather than a
 * confusing runtime traceback from a script that never had a chance to run - so this
 * runner still performs a distinct "compile" step, a {@code py_compile} syntax check of
 * every source file before the harness is actually invoked. Subprocess bookkeeping
 * (starting the process, capping captured output, reading back result files) is shared
 * with {@link LocalJavaRunner} via {@link LocalProcessSupport}.
 */
@Component
public class LocalPythonRunner implements Runner {

    private final String pythonExecutable;

    public LocalPythonRunner(@Value("${sweprep.runner.python-executable:python3}") String pythonExecutable) {
        this.pythonExecutable = pythonExecutable;
    }

    @Override
    public String languageId() {
        return "python";
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Path workDir = null;
        try {
            workDir = LocalProcessSupport.createWorkDir("sweprep-run-py-");
            LocalProcessSupport.writeFiles(workDir, request.sourceFiles());
            LocalProcessSupport.writeFiles(workDir, request.dataFiles());

            String compileError = checkSyntax(workDir, request.sourceFiles().keySet());
            if (compileError != null) {
                return ExecutionResult.compileError(compileError);
            }
            return LocalProcessSupport.run(workDir, runCommand(request), request);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new LocalJavaRunner.RunnerException("Local Python execution failed", e);
        } finally {
            LocalProcessSupport.deleteRecursively(workDir);
        }
    }

    /**
     * Compiles (in the {@code py_compile} sense - a syntax check with no execution)
     * every source file so a submission with a syntax error is reported as a compile
     * error, matching {@link LocalJavaRunner}'s outcome shape, rather than surfacing as
     * a runtime traceback from a harness that could never have run it. Returns combined
     * diagnostics on failure, or {@code null} when every file is syntactically valid.
     */
    private String checkSyntax(Path workDir, Iterable<String> sourceNames)
            throws IOException, InterruptedException {
        StringBuilder diagnostics = new StringBuilder();
        ExecutorService drain = Executors.newSingleThreadExecutor();
        try {
            for (String name : sourceNames) {
                List<String> command = List.of(pythonExecutable, "-m", "py_compile", name);
                Process process = new ProcessBuilder(command)
                        .directory(workDir.toFile())
                        .redirectErrorStream(true)
                        .start();
                Future<String> output = drain.submit(() -> LocalProcessSupport.drainCapped(process.getInputStream()));
                boolean finished = process.waitFor(30, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    process.waitFor();
                    diagnostics.append(name).append(": syntax check timed out\n");
                    continue;
                }
                if (process.exitValue() != 0) {
                    String captured = readQuietly(output);
                    diagnostics.append(captured.isBlank() ? name + ": failed to compile\n" : captured);
                }
            }
        } finally {
            drain.shutdownNow();
        }
        return diagnostics.isEmpty() ? null : diagnostics.toString().strip();
    }

    private static String readQuietly(Future<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> runCommand(ExecutionRequest request) {
        List<String> command = new ArrayList<>();
        command.add(pythonExecutable);
        command.add(request.mainClass());
        command.addAll(request.args());
        return command;
    }
}
