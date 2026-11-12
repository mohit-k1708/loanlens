package com.loanlens.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanlens.dto.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the request body of {@code /api/ai/**} (default 8 KB; a question is at most 500 characters). A declared
 * Content-Length above the cap is refused with 413 before anything is read; a body of unknown length is cut off at the cap.
 *
 * <p>Registered explicitly in {@link AiConfig} (not component-scanned), so MVC test slices that do not load the AI
 * configuration are unaffected.
 */
public class AiRequestSizeFilter extends OncePerRequestFilter {

    private final AiProperties properties;
    private final ObjectMapper mapper;

    public AiRequestSizeFilter(AiProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/ai/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int max = properties.maxRequestBytes();
        if (request.getContentLengthLong() > max) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiErrorResponse body = new ApiErrorResponse(Instant.now(), 413, HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase(),
                    "The request is too large. Keep the question under 500 characters.", request.getRequestURI(), Map.of(), List.of(), null);
            mapper.writeValue(response.getOutputStream(), body);
            return;
        }
        chain.doFilter(new LimitedRequest(request, max), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final int max;

        LimitedRequest(HttpServletRequest request, int max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0 && ++read > max) {
                        throw new IOException("Request body too large");
                    }
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = delegate.read(buf, off, len);
                    if (n > 0 && (read += n) > max) {
                        throw new IOException("Request body too large");
                    }
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
