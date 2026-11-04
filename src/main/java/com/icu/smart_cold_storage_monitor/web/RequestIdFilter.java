package com.icu.smart_cold_storage_monitor.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request an id, exposed as the {@code X-Request-Id} response header, a request attribute used in
 * {@link ApiError} bodies, and the {@code requestId} logging MDC key. Runs before the security filter chain so
 * security error responses carry the id too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    /** Returns the id assigned to this request, or a fresh one if the filter did not run. */
    public static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute(ATTRIBUTE);
        return id instanceof String s ? s : UUID.randomUUID().toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put("requestId", id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
