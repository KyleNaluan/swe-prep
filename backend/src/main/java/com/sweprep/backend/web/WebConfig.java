package com.sweprep.backend.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows a browser origin other than the backend's own to call the {@code /api}
 * endpoints. The list comes from the single {@code sweprep.web.allowed-origins}
 * property (also read by the actuator's own CORS config in {@code application.yml}, so
 * there is exactly one place that names allowed origins, not two) - see issue #34.
 *
 * <p>In local dev this is not actually load-bearing for the app's own calls: the Vite
 * dev server proxies {@code /api} to the backend (see {@code frontend/vite.config.ts}),
 * so the browser's calls are same-origin whether the page was opened as localhost or a
 * tailnet address, and never hit this check at all. That holds for POSTs too only because
 * the proxy strips the browser's {@code Origin} header before forwarding
 * ({@code dropOriginHeader} in {@code vite.config.ts}) - a same-origin POST still carries
 * one, and without the strip Spring would see a foreign origin and 403 it. It still
 * matters for anything that talks to the backend directly - a non-proxied client, or a
 * future production build.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(
            @Value("${sweprep.web.allowed-origins:http://localhost:5173,http://localhost:5174}")
                    String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST");
    }
}
