package com.sweprep.backend.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The stub and harness are generated from the language-neutral signature, not
 * hand-written per problem: a different signature yields correspondingly
 * different Java, driven only by the declared types.
 */
class JavaLanguageAdapterTest {

    private final JavaLanguageAdapter adapter = new JavaLanguageAdapter();

    @Test
    void stubIsDerivedFromTheSignature() {
        Signature signature = new Signature(
                "twoSum",
                List.of(new Parameter("nums", DataType.INT_ARRAY), new Parameter("target", DataType.INT)),
                DataType.INT_ARRAY);

        String stub = adapter.generateStub(signature);

        assertThat(stub).contains("public int[] twoSum(int[] nums, int target)");
        assertThat(stub).contains("class Solution");
    }

    @Test
    void adifferentSignatureYieldsDifferentGeneratedJava() {
        Signature booleanReturning = new Signature(
                "isAnagram",
                List.of(new Parameter("s", DataType.STRING), new Parameter("t", DataType.STRING)),
                DataType.BOOLEAN);

        String stub = adapter.generateStub(booleanReturning);
        String harness = adapter.generateHarness(booleanReturning).sourceFiles().values().iterator().next();

        assertThat(stub).contains("public boolean isAnagram(String s, String t)");
        assertThat(harness).contains("mapper.convertValue(input.get(0), String.class)");
        assertThat(harness).contains("solution.isAnagram(arg0, arg1)");
    }
}
