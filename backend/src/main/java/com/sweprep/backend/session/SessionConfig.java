package com.sweprep.backend.session;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the {@link Clock} the session loop reads "today" from (issue #19). A bean
 * rather than a bare {@code LocalDate.now()} so the day boundary is one injectable
 * seam - tests pin it to prove streaks across day boundaries, and if the app ever needs
 * a fixed practice zone it changes here alone.
 */
@Configuration
public class SessionConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
