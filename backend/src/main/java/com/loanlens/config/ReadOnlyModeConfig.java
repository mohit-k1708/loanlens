package com.loanlens.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Turns on {@link ReadOnlyModeFilter} when {@code loanlens.read-only=true} (env {@code LOANLENS_READ_ONLY}). Off by default. */
@Configuration
@ConditionalOnProperty(name = "loanlens.read-only", havingValue = "true")
public class ReadOnlyModeConfig {

    @Bean
    FilterRegistrationBean<ReadOnlyModeFilter> readOnlyModeFilter(ObjectMapper mapper) {
        FilterRegistrationBean<ReadOnlyModeFilter> registration = new FilterRegistrationBean<>(new ReadOnlyModeFilter(mapper));
        registration.addUrlPatterns("/api/*");
        registration.setName("readOnlyModeFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
