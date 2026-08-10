package com.sweprep.backend.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for the topic-to-pattern lookup the pattern-identification rep derives from. */
class PatternCatalogTest {

    @Test
    void recognisesAKnownTopicTag() {
        assertThat(PatternCatalog.patternFor(List.of("two-pointers"))).contains("Two pointers");
    }

    @Test
    void normalisesCaseAndPunctuationBeforeMatching() {
        assertThat(PatternCatalog.patternFor(List.of("Hash-Map"))).contains("Hash map lookup");
    }

    @Test
    void firstRecognisedTopicWinsWhenSeveralAreDeclared() {
        assertThat(PatternCatalog.patternFor(List.of("array", "sliding-window", "hash-map")))
                .contains("Sliding window");
    }

    @Test
    void noRecognisedTopicYieldsEmptyRatherThanAGuess() {
        assertThat(PatternCatalog.patternFor(List.of("array", "matrix"))).isEmpty();
        assertThat(PatternCatalog.patternFor(List.of())).isEmpty();
    }

    @Test
    void distractorsNeverIncludeTheCorrectPatternAndEachNamesAMisconception() {
        List<PatternCatalog.Distractor> distractors = PatternCatalog.distractors("Two pointers", 3);

        assertThat(distractors).hasSize(3);
        assertThat(distractors).extracting(PatternCatalog.Distractor::label).doesNotContain("Two pointers");
        assertThat(distractors).allSatisfy(d -> assertThat(d.misconception()).isNotBlank());
    }
}
