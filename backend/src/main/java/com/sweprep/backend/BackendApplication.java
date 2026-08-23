package com.sweprep.backend;

import com.sweprep.backend.advisor.ComplexityAdvisorProperties;
import com.sweprep.backend.commit.SolutionCommitProperties;
import com.sweprep.backend.complexity.ComplexityProperties;
import com.sweprep.backend.content.ContentProperties;
import com.sweprep.backend.learned.LearnedProperties;
import com.sweprep.backend.readiness.ReadinessProperties;
import com.sweprep.backend.reps.RepProperties;
import com.sweprep.backend.scheduler.ChallengeSchedulerProperties;
import com.sweprep.backend.session.StreakProperties;
import com.sweprep.backend.sql.SqlFixtureProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    ContentProperties.class,
    RepProperties.class,
    LearnedProperties.class,
    ComplexityProperties.class,
    ChallengeSchedulerProperties.class,
    SolutionCommitProperties.class,
    ReadinessProperties.class,
    StreakProperties.class,
    SqlFixtureProperties.class,
    ComplexityAdvisorProperties.class
})
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
