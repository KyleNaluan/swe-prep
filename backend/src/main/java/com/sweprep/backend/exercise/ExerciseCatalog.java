package com.sweprep.backend.exercise;

import java.util.List;
import java.util.Optional;

/**
 * The set of exercises the app can serve. Hardcoding is gone (issue #14): the
 * only implementation reads real content from the private content repo via a
 * gitignored local path. This interface is the seam the web layer depends on, so
 * how content is stored never leaks into it.
 */
public interface ExerciseCatalog {

    /** Every loaded exercise, in a stable order. */
    List<Exercise> all();

    /** The exercise with this id, if the content set has one. */
    Optional<Exercise> byId(String id);
}
