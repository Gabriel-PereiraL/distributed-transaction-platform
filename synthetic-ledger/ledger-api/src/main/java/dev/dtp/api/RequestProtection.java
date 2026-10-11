package dev.dtp.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-100)
class RequestProtection extends OncePerRequestFilter {
    static final String CORRELATION_ATTRIBUTE = "dtp.correlation";
    private static final int MAX_BODY = 16_384;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Cache-Control", "no-store");
        String correlation = request.getHeader("X-Correlation-Id");
        if (correlation == null || !correlation.matches("[A-Za-z0-9_-]{1,64}")) correlation = UUID.randomUUID().toString();
        request.setAttribute(CORRELATION_ATTRIBUTE, correlation);
        response.setHeader("X-Correlation-Id", correlation);
        String prior = MDC.get("correlation_id");
        MDC.put("correlation_id", correlation);
        try {
            if (request.getContentLengthLong() > MAX_BODY) { tooLarge(response); return; }
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) { tooLarge(response); return; }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input = new ByteArrayInputStream(bytes);
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous API only."); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
            }, response);
        } finally {
            if (prior == null) MDC.remove("correlation_id"); else MDC.put("correlation_id", prior);
        }
    }

    private static void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413); response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Payload too large\",\"status\":413,\"code\":\"request.too_large\"}");
    }
}
