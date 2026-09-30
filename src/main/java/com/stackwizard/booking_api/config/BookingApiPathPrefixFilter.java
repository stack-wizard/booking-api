package com.stackwizard.booking_api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ALB routes {@code /booking-api/*} to this service without stripping the prefix.
 * Controllers are mapped under {@code /api/**}; this filter rewrites the servlet path
 * so public URLs like {@code /booking-api/api/availability} hit the same handlers as {@code /api/availability}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BookingApiPathPrefixFilter extends OncePerRequestFilter {
    static final String PREFIX = "/booking-api";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String pathWithinApp = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;

        if (pathWithinApp.equals(PREFIX) || pathWithinApp.startsWith(PREFIX + "/")) {
            String stripped = pathWithinApp.substring(PREFIX.length());
            if (stripped.isEmpty()) {
                stripped = "/";
            }
            filterChain.doFilter(new PrefixedRequest(request, contextPath + stripped), response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static final class PrefixedRequest extends HttpServletRequestWrapper {
        private final String rewrittenUri;

        private PrefixedRequest(HttpServletRequest request, String rewrittenUri) {
            super(request);
            this.rewrittenUri = rewrittenUri;
        }

        @Override
        public String getRequestURI() {
            return rewrittenUri;
        }

        @Override
        public String getServletPath() {
            String contextPath = getContextPath();
            if (contextPath != null && !contextPath.isEmpty() && rewrittenUri.startsWith(contextPath)) {
                return rewrittenUri.substring(contextPath.length());
            }
            return rewrittenUri;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer();
            String scheme = getScheme();
            url.append(scheme).append("://").append(getServerName());
            int port = getServerPort();
            if (("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443)) {
                url.append(':').append(port);
            }
            url.append(rewrittenUri);
            return url;
        }
    }
}
