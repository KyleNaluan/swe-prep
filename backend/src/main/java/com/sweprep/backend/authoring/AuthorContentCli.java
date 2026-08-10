package com.sweprep.backend.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.authoring.RepDeriver.DerivationResult;
import com.sweprep.backend.exercise.Exercise;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The single command issue #24 asks for: takes a {@link ProblemSpec} (statement,
 * cases, a reference solution - the authoring unit) and produces a complete
 * content entry - the {@code CHALLENGE} exercise plus every warm-up rep {@link
 * RepDeriver} could derive from it - in a local clone of the private
 * swe-prep-content repo, after presenting everything for human review and
 * requiring an explicit accept.
 *
 * <p>Run via {@code scripts/author-content.sh <problem-spec.json> [content-dir]}
 * (wraps {@code mvn -q compile exec:java}), or directly:
 *
 * <pre>
 * cd backend
 * ./mvnw -q compile exec:java -Dexec.mainClass=com.sweprep.backend.authoring.AuthorContentCli \
 *     -Dexec.args="--problem /path/to/two-sum.spec.json --content-dir ../content"
 * </pre>
 *
 * <p>The flow is linear and each step is a distinct acceptance criterion (issue
 * #24): {@link SafetyGuard} refuses a destination inside this public repo before
 * anything else runs; {@link RepDeriver} derives the challenge and its reps,
 * verifying the reference solution empirically rather than trusting it;
 * {@link ReviewPresenter} prints everything - including generated spot-the-bug
 * and fill-in-the-blank reps, each carrying the exact mutation applied - for a
 * human to read; nothing is written until that human accepts, at the interactive
 * prompt this class owns.
 */
public final class AuthorContentCli {

    private AuthorContentCli() {}

    public static void main(String[] args) {
        int exitCode = run(
                args, System.out, System.err, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** Testable entry point: parses arguments, runs the flow, prints the outcome. Returns a process exit code. */
    static int run(String[] args, PrintStream out, PrintStream err, BufferedReader stdin) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (AuthoringException e) {
            err.println("error: " + e.getMessage());
            err.println();
            err.print(USAGE);
            return 2;
        }
        try {
            ProblemSpec spec = ProblemSpecParser.parse(parsed.problemFile());
            AuthorResult result = author(spec, parsed.contentDir(), parsed.autoAccept(), out, stdin);
            if (!result.accepted()) {
                out.println();
                out.println("Aborted - nothing was written.");
                return 1;
            }
            out.println();
            out.println("Wrote " + result.writtenFiles().size() + " file(s) to " + parsed.contentDir() + ":");
            result.writtenFiles().forEach(p -> out.println("  " + p));
            out.println();
            out.println(
                    "Next: review the diff in your swe-prep-content clone (git diff / git status there) "
                            + "and open a PR against swe-prep-content - this tool never pushes or opens a PR "
                            + "for you.");
            return 0;
        } catch (AuthoringException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    /** The outcome of one {@link #author} call. */
    record AuthorResult(boolean accepted, List<Path> writtenFiles) {}

    /**
     * The whole flow as one call, independent of process argv/exit-code plumbing so
     * tests can drive it directly: safety check, derive, present for review, gate on
     * acceptance, write on accept.
     */
    static AuthorResult author(
            ProblemSpec spec, Path contentDir, boolean autoAccept, PrintStream out, BufferedReader stdin) {
        SafetyGuard.requireSafeContentDir(contentDir);

        DerivationResult derived = new RepDeriver().derive(spec);
        requireNoCollisions(derived, contentDir);

        new ReviewPresenter(out).present(derived);

        boolean accepted = autoAccept || confirm(out, stdin);
        if (!accepted) {
            return new AuthorResult(false, List.of());
        }

        ContentWriter writer = new ContentWriter(new ObjectMapper());
        List<Path> written = new ArrayList<>();

        writer.writeExercise(derived.challenge(), contentDir);
        written.add(contentDir.resolve(derived.challenge().id() + ".json"));

        writer.writeSolution(spec.id(), spec.referenceSolution(), contentDir);
        written.add(contentDir.resolve("solutions").resolve(spec.id() + ".java"));

        for (Exercise rep : derived.reps()) {
            writer.writeExercise(rep, contentDir);
            written.add(contentDir.resolve(rep.id() + ".json"));
        }
        return new AuthorResult(true, List.copyOf(written));
    }

    /** Refuses to silently clobber a file that already exists for any id this run would write. */
    private static void requireNoCollisions(DerivationResult derived, Path contentDir) {
        List<String> ids = new ArrayList<>();
        ids.add(derived.challenge().id());
        derived.reps().forEach(rep -> ids.add(rep.id()));
        List<String> collisions =
                ids.stream().filter(id -> Files.exists(contentDir.resolve(id + ".json"))).toList();
        if (!collisions.isEmpty()) {
            throw new AuthoringException(
                    "refusing to overwrite existing content file(s) already in " + contentDir + ": "
                            + collisions + " - remove them first, or change the problem id");
        }
    }

    private static boolean confirm(PrintStream out, BufferedReader stdin) {
        out.println();
        out.print("Accept and write this content entry? [y/N] ");
        out.flush();
        try {
            String line = stdin.readLine();
            return line != null
                    && (line.strip().equalsIgnoreCase("y") || line.strip().equalsIgnoreCase("yes"));
        } catch (IOException e) {
            return false;
        }
    }

    private static final String USAGE =
            """
            Usage: author-content --problem <problem-spec.json> --content-dir <path> [--yes]

              --problem <path>      Path to a problem spec JSON (see ProblemSpecParser's javadoc).
              --content-dir <path>  Path to a local clone of the private swe-prep-content repo.
                                     Defaults to $SWEPREP_CONTENT_PATH if set.
              --yes                 Skip the interactive accept prompt (the review still prints
                                     first) - for scripted/CI use, not the normal authoring flow.
            """;

    private record Args(Path problemFile, Path contentDir, boolean autoAccept) {

        static Args parse(String[] args) {
            String problem = null;
            String contentDir = null;
            boolean yes = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--problem" -> problem = requireValue(args, ++i);
                    case "--content-dir" -> contentDir = requireValue(args, ++i);
                    case "--yes" -> yes = true;
                    default -> throw new AuthoringException("unknown argument '" + args[i] + "'");
                }
            }
            if (problem == null) {
                throw new AuthoringException("--problem is required");
            }
            if (contentDir == null) {
                contentDir = System.getenv("SWEPREP_CONTENT_PATH");
            }
            if (contentDir == null) {
                throw new AuthoringException("--content-dir is required (or set SWEPREP_CONTENT_PATH)");
            }
            return new Args(Path.of(problem), Path.of(contentDir), yes);
        }

        private static String requireValue(String[] args, int index) {
            if (index >= args.length) {
                throw new AuthoringException("missing value after " + args[index - 1]);
            }
            return args[index];
        }
    }
}
