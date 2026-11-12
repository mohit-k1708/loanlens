package com.loanlens.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed access to the {@code loanlens.*} configuration. */
@ConfigurationProperties(prefix = "loanlens")
public record LoanLensProperties(Cors cors, Seed seed) {

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }

    public record Seed(boolean enabled) {
    }
}
