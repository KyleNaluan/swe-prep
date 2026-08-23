package com.sweprep.backend.advisor;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.sweprep.backend.exercise.Exercise;
import org.springframework.stereotype.Component;

/**
 * The one real {@link ComplexityAdvisor}: one short call to the Anthropic API,
 * through the official Java SDK (never a hand-rolled HTTP client, per issue #83's
 * instruction), asking for a structured {@link ModelComplexityReading} so the
 * answer is a validated bucket-plus-reasoning pair, never free text this class has
 * to parse itself.
 *
 * <p>A single {@code @Component}, always registered, so tests never need Spring's
 * conditional-bean machinery to exercise the key-absent path - {@link #available()}
 * (and every other {@link ComplexityAdvisor}) answers with no network call, and every
 * caller (see {@code AttemptService#secondOpinion}) is required to check it before
 * ever reaching {@link #read}. When {@link ComplexityAdvisorProperties#apiKey()} is
 * blank, no {@link AnthropicClient} is even constructed.
 */
@Component
public class AnthropicComplexityAdvisor implements ComplexityAdvisor {

    private final ComplexityAdvisorProperties properties;
    private final AnthropicClient client;

    public AnthropicComplexityAdvisor(ComplexityAdvisorProperties properties) {
        this.properties = properties;
        this.client = properties.apiKey() == null
                ? null
                : AnthropicOkHttpClient.builder().apiKey(properties.apiKey()).build();
    }

    @Override
    public boolean available() {
        return properties.apiKey() != null;
    }

    @Override
    public ModelComplexityReading read(Exercise exercise, String submissionSource, String language) {
        if (!available()) {
            // Every caller must check available() first (see the class javadoc); this
            // guard exists so a programming mistake fails loudly rather than silently
            // reaching for a null client.
            throw new ComplexityAdvisorException("No Anthropic API key is configured");
        }
        String prompt = ComplexityAdvisorPrompt.build(exercise, submissionSource, language);
        StructuredMessageCreateParams<ModelComplexityReading> params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(4096L)
                .outputConfig(ModelComplexityReading.class)
                .addUserMessage(prompt)
                .build();
        try {
            return client.messages().create(params).content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(structuredText -> structuredText.text())
                    .findFirst()
                    .orElseThrow(() -> new ComplexityAdvisorException(
                            "The model returned no structured complexity reading"));
        } catch (AnthropicException e) {
            throw new ComplexityAdvisorException("Second opinion call failed: " + e.getMessage(), e);
        }
    }
}
