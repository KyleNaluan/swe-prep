package com.sweprep.backend.web;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sweprep.backend.attempt.AttemptNotFoundException;
import com.sweprep.backend.attempt.LessonReadService;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.testsupport.Fixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The lesson read endpoints over the HTTP wire (issue #46/#41): a lesson lists and reads
 * with its ungraded self-explanation prompts, and there is deliberately no grade path - a
 * lesson is read, never attempted.
 */
@WebMvcTest(LessonController.class)
class LessonControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentCatalog catalog;

    @MockitoBean
    private LessonReadService reads;

    @Test
    void readsALessonWithItsSelfExplanationPrompts() throws Exception {
        when(catalog.contentById("lesson-indexes"))
                .thenReturn(Optional.of(Fixtures.lessonWithPrompts()));

        mockMvc.perform(get("/api/lessons/lesson-indexes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Why an index sometimes is not used"))
                .andExpect(jsonPath("$.prompts.length()").value(2))
                .andExpect(jsonPath("$.prompts[0].prompt")
                        .value(org.hamcrest.Matchers.containsString("wrapping an indexed column")))
                .andExpect(jsonPath("$.prompts[0].modelAnswer")
                        .value(org.hamcrest.Matchers.containsString("raw column values")));
    }

    @Test
    void readsAStructuredLessonBodyWithEveryBlockKind() throws Exception {
        when(catalog.contentById("lesson-hash-maps"))
                .thenReturn(Optional.of(Fixtures.lessonWithStructuredBody()));

        mockMvc.perform(get("/api/lessons/lesson-hash-maps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(7))
                .andExpect(jsonPath("$.body[0].kind").value("heading"))
                .andExpect(jsonPath("$.body[0].level").value(2))
                .andExpect(jsonPath("$.body[1].kind").value("paragraph"))
                .andExpect(jsonPath("$.body[2].kind").value("example"))
                .andExpect(jsonPath("$.body[2].language").value("java"))
                .andExpect(jsonPath("$.body[2].output").value("3"))
                .andExpect(jsonPath("$.body[3].kind").value("callout"))
                .andExpect(jsonPath("$.body[3].style").value("WARNING"))
                .andExpect(jsonPath("$.body[4].kind").value("heading"))
                .andExpect(jsonPath("$.body[5].kind").value("list"))
                .andExpect(jsonPath("$.body[5].ordered").value(false))
                .andExpect(jsonPath("$.body[5].items.length()").value(2));
    }

    @Test
    void aLegacyLessonWithNoStructuredBodySendsAnEmptyBodyArray() throws Exception {
        // lessonWithPrompts() carries no body blocks - every real lesson today. The frontend's
        // legacy fallback (rendering `statement` as a single paragraph) relies on this being an
        // empty array, never a missing field, so it never has to distinguish the two.
        when(catalog.contentById("lesson-indexes"))
                .thenReturn(Optional.of(Fixtures.lessonWithPrompts()));

        mockMvc.perform(get("/api/lessons/lesson-indexes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(0));
    }

    @Test
    void listsOnlyLessons() throws Exception {
        // The wide content view also holds exercises; the lesson list must show only lessons.
        when(catalog.allContent())
                .thenReturn(List.of(Fixtures.lessonWithPrompts(), Fixtures.concept()));

        mockMvc.perform(get("/api/lessons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("lesson-indexes"))
                .andExpect(jsonPath("$[0].promptCount").value(2))
                // The TreeBrowser's concept tier groups by topic (issue #90) without a
                // second fetch, so the summary must carry it.
                .andExpect(jsonPath("$[0].topics").value(org.hamcrest.Matchers.contains("databases")))
                // The family filter group (issue #90) reads the same tag Role Families
                // already uses.
                .andExpect(jsonPath("$[0].family")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("BACKEND", "DATA")));
    }

    @Test
    void fetchingAnExerciseIdAsALessonIsNotFound() throws Exception {
        when(catalog.contentById("concept-demo")).thenReturn(Optional.of(Fixtures.concept()));

        mockMvc.perform(get("/api/lessons/concept-demo")).andExpect(status().isNotFound());
    }

    @Test
    void readingALessonRecordsItSoItsChecksAreSeeded() throws Exception {
        // The read is what opts an inactive-family lesson's Checks into the warm-up (issue #40);
        // the endpoint just records it and returns 200. The seeding effect is proven end to end
        // in WarmupServiceTest.
        mockMvc.perform(post("/api/lessons/lesson-indexes/read")).andExpect(status().isOk());
        verify(reads).recordRead("lesson-indexes");
    }

    @Test
    void readingANonLessonIdIsNotFound() throws Exception {
        org.mockito.Mockito.doThrow(new AttemptNotFoundException("nope"))
                .when(reads)
                .recordRead("concept-demo");

        mockMvc.perform(post("/api/lessons/concept-demo/read")).andExpect(status().isNotFound());
    }
}
