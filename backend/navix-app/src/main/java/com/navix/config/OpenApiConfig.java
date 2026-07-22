package com.navix.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc OpenAPI configuration.
 *
 * TODO: add security schemes (borrower token, staff auth) and server entries
 *       once authentication is wired.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI navixOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("DhanBoost API")
                        .description("Salary-linked single-repayment lending platform API.")
                        .version("0.0.1-SNAPSHOT"));
    }
}
