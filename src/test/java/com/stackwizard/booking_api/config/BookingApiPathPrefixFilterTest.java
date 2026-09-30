package com.stackwizard.booking_api.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BookingApiPathPrefixFilterTest {

    private final BookingApiPathPrefixFilter filter = new BookingApiPathPrefixFilter();

    @Test
    void stripsBookingApiPrefix() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/booking-api/api/resource-maps/periods");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(org.mockito.ArgumentMatchers.argThat(req ->
                "/api/resource-maps/periods".equals(((jakarta.servlet.http.HttpServletRequest) req).getRequestURI())
                        && "/api/resource-maps/periods".equals(((jakarta.servlet.http.HttpServletRequest) req).getServletPath())
        ), eq(response));
    }

    @Test
    void leavesUnprefixedPathsAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/availability");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(MockHttpServletRequest.class), eq(response));
        assertEquals("/api/availability", request.getRequestURI());
    }
}
