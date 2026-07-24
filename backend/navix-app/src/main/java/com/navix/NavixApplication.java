package com.navix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * DhanBoost bootable application.
 *
 * Aggregates all 9 business modules (common, iam, onboarding, kyc, verification,
 * income-risk, loan, disbursement, collections) under the com.navix base package.
 *
 * TODO: nothing functional lives here yet; this is the composition root only.
 */
@SpringBootApplication(scanBasePackages = "com.navix")
@EntityScan("com.navix")
@EnableJpaRepositories("com.navix")
@ConfigurationPropertiesScan("com.navix")
@EnableJpaAuditing
public class NavixApplication {

    public static void main(String[] args) {
        SpringApplication.run(NavixApplication.class, args);
    }
}
