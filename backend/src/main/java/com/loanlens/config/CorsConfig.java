package com.loanlens.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the React frontend (a different origin in development) call the API from the browser.
 * Allowed origins are configuration, not code. This is NOT authentication.
 */
@Configuration
@EnableConfigurationProperties(LoanLensProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private final LoanLensProperties properties;

    public CorsConfig(LoanLensProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = properties.cors().allowedOrigins().toArray(String[]::new);
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept", "Authorization")
                .maxAge(3600);
    }
}
