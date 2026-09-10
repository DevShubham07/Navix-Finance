package com.navix.loan.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The pool bulk lead imports run on.
 *
 * <p>Deliberately tiny. An import is a long, CPU- and DB-heavy walk over a 200k-row file, and the
 * service runs as a single Fargate task with 1 vCPU / 2 GB (aws.md §2) that is also serving live
 * borrower traffic. One import at a time is the point; a second waits in the queue rather than
 * competing for the same core, and {@code CallerRunsPolicy} applies back-pressure instead of
 * silently dropping a job the operator has been told is queued.
 *
 * <p>{@code @EnableAsync} is not declared here — {@code navix-notification}'s {@code AsyncConfig}
 * already owns it for the whole application context, and declaring it twice would be redundant.
 */
@Configuration
public class LeadImportAsyncConfig {

    @Bean("leadImportExecutor")
    public Executor leadImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("lead-import-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
