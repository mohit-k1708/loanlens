package com.loanlens.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanlens.dto.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Public-demo protection (loanlens.read-only=true). The API has no authentication, so when it is exposed on the internet
 * every request that could CHANGE data (POST, PUT, PATCH, DELETE on /api/**) is refused with 403.
 *
 * <p>The one exception is {@code POST /api/ai/explain}: it only reads data to produce an explanation (it writes nothing),
 * and it is protected by its own size limit and rate limit.
 *
 * <p>Registered explicitly in {@link ReadOnlyModeConfig}, only when the property is true.
 */
public class ReadOnlyModeFilter extends OncePerRequestFilter {

    static final String ALLOWED_POST = "/api/ai/explain";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final ObjectMapper mapper;

    public ReadOnlyModeFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        boolean allowed = SAFE_METHODS.contains(method)
                || ("POST".equals(method) && ALLOWED_POST.equals(request.getRequestURI()));
        if (allowed) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse body = new ApiErrorResponse(Instant.now(), 403, HttpStatus.FORBIDDEN.getReasonPhrase(),
                "This is a public read-only demo: changes are disabled. Run LoanLens locally to add or edit records.",
                request.getRequestURI(), Map.of(), List.of(), null);
        mapper.writeValue(response.getOutputStream(), body);
    }
}
