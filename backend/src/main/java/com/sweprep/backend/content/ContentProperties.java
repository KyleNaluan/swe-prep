package com.sweprep.backend.content;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the app reads exercise content from. The path points at a local clone of
 * the private content repo and is deliberately <em>not</em> a submodule: content
 * lives outside this public repo entirely and the path is gitignored, so no
 * problem statement, test data or reference solution is ever committed here (the
 * public-engine/private-content decision, issue #4).
 *
 * @param path directory holding the exercise {@code *.json} files; relative paths
 *             resolve against the process working directory
 */
@ConfigurationProperties(prefix = "sweprep.content")
public record ContentProperties(String path) {}
