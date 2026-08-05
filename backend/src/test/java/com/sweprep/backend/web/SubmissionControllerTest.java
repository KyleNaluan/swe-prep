package com.sweprep.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.grader.TestCaseGrader;
import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.runner.LocalJavaRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the tracer bullet end to end through the HTTP wire: the exercise is
 * served to the editor, and a submission POSTed as JSON is compiled, run against
 * the language-neutral cases, and answered with a verdict. Uses the real grader,
 * runner and adapter - only the datasource is left out, since this ticket
 * persists nothing.
 */
@WebMvcTest(SubmissionController.class)
@Import({ExerciseCatalog.class, JavaLanguageAdapter.class, LocalJavaRunner.class, TestCaseGrader.class})
class SubmissionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void servesTheExerciseWithACompilingStub() throws Exception {
        mockMvc.perform(get("/api/exercise"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Two Sum"))
                .andExpect(jsonPath("$.language").value("java"))
                .andExpect(jsonPath("$.stub").value(org.hamcrest.Matchers.containsString("class Solution")))
                .andExpect(jsonPath("$.stub").value(org.hamcrest.Matchers.containsString("twoSum")));
    }

    @Test
    void runningACorrectSolutionReportsAllCasesPassed() throws Exception {
        String code =
                """
                import java.util.HashMap;
                import java.util.Map;

                class Solution {
                    public int[] twoSum(int[] nums, int target) {
                        Map<Integer, Integer> seen = new HashMap<>();
                        for (int i = 0; i < nums.length; i++) {
                            int need = target - nums[i];
                            if (seen.containsKey(need)) {
                                return new int[] {seen.get(need), i};
                            }
                            seen.put(nums[i], i);
                        }
                        return new int[] {-1, -1};
                    }
                }
                """;

        mockMvc.perform(post("/api/exercise/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RunRequest(code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PASSED"))
                .andExpect(jsonPath("$.passed").value(4))
                .andExpect(jsonPath("$.total").value(4));
    }

    @Test
    void runningCodeThatDoesNotCompileReportsACompileError() throws Exception {
        String code =
                """
                class Solution {
                    public int[] twoSum(int[] nums, int target) {
                        return notAVariable;
                    }
                }
                """;

        mockMvc.perform(post("/api/exercise/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RunRequest(code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("COMPILE_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Solution.java")));
    }
}
