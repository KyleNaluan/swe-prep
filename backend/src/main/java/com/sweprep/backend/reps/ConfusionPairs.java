package com.sweprep.backend.reps;

import com.sweprep.backend.attempt.SubmissionRepository.FailedResponse;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The <em>confusability</em> relation between rep topics, derived from what learners
 * actually mistake for what (design revision t3, section 4.2). Interleaving trains
 * discrimination only when it juxtaposes <em>confusable</em> material - two-pointer
 * against sliding window, {@code INNER} against a {@code LEFT JOIN} fan-out - and the
 * signal for which patterns are confusable is already in the record: on a wrong Choice
 * answer, the distractor a learner picked names the pattern they mistook the real one
 * for. This value object turns that history into a symmetric, weighted topic relation
 * the {@link WarmupSelector} consults when ordering a set.
 *
 * <p>It is deliberately immutable and pure - {@link #derive} takes the raw wrong
 * answers and the catalog and computes the relation with no database - so its
 * behaviour, including its graceful degradation on a cold history (every install's
 * current state: no attempts recorded yet, so no pairs, so the selector falls back to
 * its family/gating/cap interleaving alone), is unit-testable without one.
 *
 * <p><b>Why a distractor names a pattern.</b> A pattern-identification rep's options
 * are the pattern names themselves, and its answer key is the correct one. So the label
 * a learner picked, when it is the correct answer of some <em>other</em> rep, resolves
 * to that other rep's topic - which is precisely "the pattern they confused this one
 * with". Cross-domain confusion is not modelled here because the audit is explicit that
 * mixing unrelated domains is spacing, not discrimination training; the selector only
 * rewards a confusable pair when the two reps also share a domain.
 */
public final class ConfusionPairs {

    private static final ConfusionPairs EMPTY = new ConfusionPairs(Map.of());

    /** topic -&gt; (confusable topic -&gt; how often the two were confused), symmetric. */
    private final Map<String, Map<String, Long>> weights;

    private ConfusionPairs(Map<String, Map<String, Long>> weights) {
        this.weights = weights;
    }

    /** The empty relation - no recorded confusion, so no pair is preferred over another. */
    public static ConfusionPairs empty() {
        return EMPTY;
    }

    /**
     * How often {@code topicA} and {@code topicB} were confused for each other, in
     * either direction; {@code 0} when they never were (or either is {@code null}). A
     * higher weight is a stronger reason to juxtapose the two.
     */
    public long weight(String topicA, String topicB) {
        if (topicA == null || topicB == null) {
            return 0;
        }
        Map<String, Long> row = weights.get(topicA);
        if (row == null) {
            return 0;
        }
        return row.getOrDefault(topicB, 0L);
    }

    /** Whether any confusion at all has been recorded - false on a cold history. */
    public boolean isEmpty() {
        return weights.isEmpty();
    }

    /**
     * Derives the relation from every wrong Choice answer and the loaded catalog. For
     * each wrong answer, the exercise's own topic is the pattern that was actually being
     * asked about, and the picked distractor - when it is the canonical (answer-key)
     * label of some other rep - resolves to the pattern it was confused with; that pair
     * is recorded. Wrong answers on content that is not loaded, that carries no topic, or
     * whose distractor names no known pattern contribute nothing, so the derivation
     * degrades cleanly rather than failing.
     *
     * @param failed  every wrong Choice submission as (exerciseId, pickedResponse)
     * @param catalog every loaded exercise, used to resolve ids and labels to topics
     */
    public static ConfusionPairs derive(List<FailedResponse> failed, List<Exercise> catalog) {
        Map<String, String> topicById = new HashMap<>();
        Map<String, String> topicByLabel = new HashMap<>();
        for (Exercise exercise : catalog) {
            String topic = primaryTopic(exercise);
            if (topic == null) {
                continue;
            }
            topicById.put(exercise.id(), topic);
            String label = answerLabel(exercise);
            if (label != null) {
                // First writer wins, so the resolution is deterministic when two reps
                // share a correct label; the pairing below skips same-topic matches anyway.
                topicByLabel.putIfAbsent(label, topic);
            }
        }
        Map<String, Map<String, Long>> weights = new HashMap<>();
        for (FailedResponse response : failed) {
            String actual = topicById.get(response.exerciseId());
            if (actual == null) {
                continue;
            }
            String confusedWith = topicByLabel.get(response.response());
            if (confusedWith == null || confusedWith.equals(actual)) {
                continue;
            }
            record(weights, actual, confusedWith);
            record(weights, confusedWith, actual);
        }
        return weights.isEmpty() ? EMPTY : new ConfusionPairs(weights);
    }

    private static void record(Map<String, Map<String, Long>> weights, String from, String to) {
        weights.computeIfAbsent(from, key -> new HashMap<>()).merge(to, 1L, Long::sum);
    }

    /** The correct-answer label of a Choice rep judged by an answer key, else {@code null}. */
    private static String answerLabel(Exercise exercise) {
        if (exercise.response() instanceof Response.Choice
                && exercise.grading() instanceof Grading.AnswerKey key
                && key.expected() != null) {
            String label = key.expected().asText();
            return label.isBlank() ? null : label;
        }
        return null;
    }

    private static String primaryTopic(Exercise exercise) {
        return exercise.topics().isEmpty() ? null : exercise.topics().get(0);
    }
}
