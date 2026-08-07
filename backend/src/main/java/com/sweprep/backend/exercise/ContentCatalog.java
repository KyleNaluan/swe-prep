package com.sweprep.backend.exercise;

import java.util.List;
import java.util.Optional;

/**
 * The set of content items the app can serve, of either kind (issue #46): the seam
 * over which the catalog, scheduler and browse surface treat a loaded {@link
 * Content} uniformly. {@link ExerciseCatalog} is the narrower view onto just the
 * {@link Exercise}s, for the consumers that attempt and grade; this is the wider one
 * that also sees {@link Lesson}s.
 */
public interface ContentCatalog {

    /** Every loaded content item, of either kind, in a stable order. */
    List<Content> allContent();

    /** The content item with this id, if the content set has one. */
    Optional<Content> contentById(String id);
}
