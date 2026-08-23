package com.sweprep.backend.advisor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configures the LLM complexity second opinion (issue #83): a model's independent
 * reading of a solved submission's time complexity, shown as a third, advisory voice
 * beside the solver's self-report and the empirical scaling measurement (issue #17).
 *
 * <p>{@link #apiKey} lives only in local config, the same {@code SWEPREP_*}
 * env-var-override-of-an-{@code application.yml}-default pattern every other local
 * secret in this app uses (see {@code sweprep.sql.reader-password},
 * {@code sweprep.commit.*}) - never a committed file, never a default value. Unlike
 * those, an absent key here is not "safe for local dev" but the feature's own off
 * switch: {@link AnthropicComplexityAdvisor#available()} is {@code false} whenever
 * {@link #apiKey} is blank, and nothing upstream may call {@link
 * AnthropicComplexityAdvisor#read} without checking it first - see the class javadoc.
 *
 * @param apiKey the Anthropic API key; blank (the unset default) means the feature is
 *               simply absent, not broken
 * @param model  the model id to call, configurable so a model change is a config edit,
 *               never a code change (issue #83's explicit requirement)
 */
@ConfigurationProperties(prefix = "sweprep.complexity-advisor")
public record ComplexityAdvisorProperties(String apiKey, String model) {

    public ComplexityAdvisorProperties {
        apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey;
        model = (model == null || model.isBlank()) ? "claude-opus-5" : model;
    }
}
