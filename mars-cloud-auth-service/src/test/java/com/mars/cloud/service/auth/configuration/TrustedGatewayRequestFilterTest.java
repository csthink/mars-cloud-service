package com.mars.cloud.service.auth.configuration;

import com.mars.cloud.service.auth.application.AccountService;
import com.mars.cloud.service.auth.application.SmsLoginService;
import com.mars.cloud.service.auth.infrastructure.sms.CaptchaProvider;
import com.mars.cloud.service.auth.infrastructure.sms.SmsChallengeStore;
import com.mars.cloud.service.auth.infrastructure.sms.SmsCrypto;
import com.mars.cloud.service.auth.infrastructure.sms.SmsRiskService;
import com.mars.cloud.service.auth.infrastructure.sms.SmsSender;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TrustedGatewayRequestFilterTest {
    private static final String PHONE = "+15551234567";

    private static MockEnvironment environment(String cidrs) {
        var environment = new MockEnvironment().withProperty("mars.auth.trusted-gateway-cidrs", cidrs)
                .withProperty("management.server.port", "9101");
        environment.setActiveProfiles("test");
        return environment;
    }

    private static MockHttpServletRequest request(String peer) {
        var request = new MockHttpServletRequest("GET", "/auth/v1/me");
        request.setRemoteAddr(peer);
        request.setLocalPort(8101);
        request.setScheme("http");
        request.setServerName("backend.example");
        request.setServerPort(8101);
        request.getSession(true);
        return request;
    }

    private static void canonical(MockHttpServletRequest request, String client) {
        request.addHeader("X-Forwarded-For", client);
        request.addHeader("X-Forwarded-Host", "auth.flippoabc.com");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Port", "443");
    }

    private static HttpServletRequest forward(TrustedGatewayRequestFilter filter, MockHttpServletRequest request)
            throws Exception {
        AtomicReference<HttpServletRequest> passed = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (received, response) ->
                passed.set((HttpServletRequest) received));
        return passed.get();
    }

    @Test
    void directRequestIgnoresAllForwardingHeaders() throws Exception {
        var filter = new TrustedGatewayRequestFilter(environment("10.0.0.0/24"));
        var request = request("198.51.100.3");
        canonical(request, "192.0.2.99");
        request.addHeader("Forwarded", "for=192.0.2.99");
        var seen = forward(filter, request);
        assertEquals("198.51.100.3", seen.getRemoteAddr());
        assertEquals("http", seen.getScheme());
        assertNull(seen.getHeader("X-Forwarded-For"));
        assertNull(seen.getHeader("Forwarded"));
        assertFalse(java.util.Collections.list(seen.getHeaderNames()).contains("X-Forwarded-For"));
    }

    @Test
    void trustedGatewaySuppliesClientAddressAndExternalRequestInformation() throws Exception {
        var filter = new TrustedGatewayRequestFilter(environment("10.0.0.0/24"));
        var request = request("10.0.0.7");
        canonical(request, "192.0.2.23");
        var seen = forward(filter, request);
        assertEquals("192.0.2.23", seen.getRemoteAddr());
        assertEquals("https", seen.getScheme());
        assertTrue(seen.isSecure());
        assertEquals("auth.flippoabc.com", seen.getServerName());
        assertEquals(443, seen.getServerPort());
        assertEquals("https://auth.flippoabc.com/auth/v1/me", seen.getRequestURL().toString());
    }

    @Test
    void smsRiskReceivesDifferentSelectedClientAddresses() throws Exception {
        var filter = new TrustedGatewayRequestFilter(environment("10.0.0.0/24"));
        var crypto = mock(SmsCrypto.class);
        var sender = mock(SmsSender.class);
        var risk = mock(SmsRiskService.class);
        when(crypto.enabled()).thenReturn(true);
        when(sender.available()).thenReturn(true);
        when(risk.reserve(eq(PHONE), anyString(), eq(false))).thenReturn(SmsRiskService.Reservation.OK);
        var sms = new SmsLoginService(crypto, sender, risk, mock(SmsChallengeStore.class),
                mock(CaptchaProvider.class), mock(AccountService.class));
        for (String client : new String[]{"192.0.2.23", "192.0.2.24"}) {
            var request = request("10.0.0.7");
            canonical(request, client);
            filter.doFilter(request, new MockHttpServletResponse(), (received, response) ->
                    sms.send(PHONE, null, null, (HttpServletRequest) received));
            verify(risk).reserve(PHONE, client, false);
        }
    }

    @Test
    void trustedPeerWithMissingRepeatedOrUnexpectedHeadersGetsBadRequest() throws Exception {
        var filter = new TrustedGatewayRequestFilter(environment("10.0.0.0/24"));
        var missing = request("10.0.0.7");
        assertRejected(filter, missing);
        var duplicate = request("10.0.0.7");
        canonical(duplicate, "192.0.2.23");
        duplicate.addHeader("X-Forwarded-For", "192.0.2.24");
        assertRejected(filter, duplicate);
        var invalid = request("10.0.0.7");
        canonical(invalid, "192.0.2.23, 10.0.0.7");
        assertRejected(filter, invalid);
        var extra = request("10.0.0.7");
        canonical(extra, "192.0.2.23");
        extra.addHeader("X-Forwarded-Client-Cert", "fake");
        assertRejected(filter, extra);
    }

    @Test
    void formalEnvironmentRequiresExplicitGatewayAddresses() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new TrustedGatewayRequestFilter(environment));
    }

    @Test
    void separateManagementPortBypassesBusinessForwardingRules() throws Exception {
        var filter = new TrustedGatewayRequestFilter(environment("10.0.0.0/24"));
        var request = request("10.0.0.7");
        request.setLocalPort(9101);
        assertSame(request, forward(filter, request));
    }

    private static void assertRejected(TrustedGatewayRequestFilter filter, MockHttpServletRequest request)
            throws Exception {
        var response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> passed = new AtomicReference<>();
        filter.doFilter(request, response, (received, ignored) -> passed.set((HttpServletRequest) received));
        assertEquals(400, response.getStatus());
        assertNull(passed.get());
    }
}
