package com.sweprep.backend.attempt;

import com.sweprep.backend.exercise.Complexity;
import java.util.Objects;

/**
 * The solver's self-reported time and space complexity for a passing solution
 * (issue #17), stated before the authored target is revealed. Only {@link #time} is
 * ever checked against measurement - scaling measures wall-clock time, so a space
 * claim has nothing empirical to compare against - and is recorded purely as its own
 * articulation exercise (the same reasoning {@link
 * com.sweprep.backend.exercise.ComplexityCheck} documents on the content side).
 *
 * <p>Serialized into the single {@code attempt.complexity_claim} text column the
 * schema already carries (migration {@code V3__attempts.sql}), so this ticket needed
 * no new migration - see {@link #serialize()} / {@link #parse}.
 */
public record ComplexityClaim(Complexity time, Complexity space) {

    public ComplexityClaim {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(space, "space");
    }

    /** The compact form stored in {@code attempt.complexity_claim}. */
    public String serialize() {
        return "time=" + time.name() + ";space=" + space.name();
    }

    /** Reads back a value written by {@link #serialize()}; {@code null} for a blank/null input. */
    public static ComplexityClaim parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        Complexity time = null;
        Complexity space = null;
        for (String part : stored.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("time".equals(kv[0])) {
                time = Complexity.valueOf(kv[1]);
            } else if ("space".equals(kv[0])) {
                space = Complexity.valueOf(kv[1]);
            }
        }
        if (time == null || space == null) {
            throw new IllegalStateException("Malformed stored complexity claim: '" + stored + "'");
        }
        return new ComplexityClaim(time, space);
    }
}
