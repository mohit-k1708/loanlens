package com.loanlens.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the AI provider from configuration. With no key the app runs normally using the deterministic explanation. */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /** Caps the request body of /api/ai/** (see {@link AiRequestSizeFilter}). */
    @Bean
    FilterRegistrationBean<AiRequestSizeFilter> aiRequestSizeFilter(AiProperties properties, ObjectMapper mapper) {
        FilterRegistrationBean<AiRequestSizeFilter> registration = new FilterRegistrationBean<>(new AiRequestSizeFilter(properties, mapper));
        registration.addUrlPatterns("/api/ai/*");
        registration.setName("aiRequestSizeFilter");
        return registration;
    }

    @Bean
    AiProvider aiProvider(AiProperties properties) {
        if (properties.providerConfigured()) {
            log.info("AI explanations: provider {} with model {} (API key present, not logged).", properties.provider(), properties.model());
            return new AnthropicAiProvider(properties);
        }
        if (AiProperties.PROVIDER_ANTHROPIC.equals(properties.provider())) {
            log.info("AI explanations: no AI_API_KEY set, so explanations use the deterministic backend fallback (clearly labelled).");
        } else if (AiProperties.PROVIDER_NONE.equals(properties.provider())) {
            log.info("AI explanations: provider disabled (AI_PROVIDER=none); the deterministic backend fallback is used.");
        } else {
            log.warn("AI explanations: unsupported AI_PROVIDER '{}' (supported: anthropic, none); the deterministic fallback is used.", properties.provider());
        }
        return new UnconfiguredAiProvider(properties.provider(), properties.model());
    }
}
