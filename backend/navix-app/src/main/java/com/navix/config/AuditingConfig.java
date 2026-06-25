package com.navix.config;

import com.navix.common.security.ActorContext;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

/**
 * Supplies the current auditor for JPA auditing (@CreatedBy / @LastModifiedBy on
 * {@code BaseAuditEntity}). Resolves from {@link ActorContext}, which the demo actor filter
 * populates per request. {@code @EnableJpaAuditing} is declared on the application class.
 */
@Configuration
public class AuditingConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.ofNullable(ActorContext.get().name());
    }
}
