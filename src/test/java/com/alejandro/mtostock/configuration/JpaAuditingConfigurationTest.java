package com.alejandro.mtostock.configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AuditorAware;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JpaAuditingConfigurationTest {

    private final JpaAuditingConfiguration configuration = new JpaAuditingConfiguration();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void auditorAwareReturnsSystemWhenThereIsNoRequest() {
        RequestContextHolder.resetRequestAttributes();
        AuditorAware<String> auditorAware = configuration.auditorAware();

        assertEquals("system", auditorAware.getCurrentAuditor().orElseThrow());
    }

    @Test
    void auditorAwareReturnsActorHeaderWhenPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Actor", "alejandro");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AuditorAware<String> auditorAware = configuration.auditorAware();

        assertEquals("alejandro", auditorAware.getCurrentAuditor().orElseThrow());
    }

    @Test
    void auditorAwareTrimsActorHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Actor", "  alejandro  ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AuditorAware<String> auditorAware = configuration.auditorAware();

        assertEquals("alejandro", auditorAware.getCurrentAuditor().orElseThrow());
    }
}