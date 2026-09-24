package com.mars.cloud.service.gateway.web;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class GatewayIngressFilterTest {
    private static MockEnvironment environment(int hops, String cidrs) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("mars.gateway.proxy.trusted-hop-count", Integer.toString(hops))
                .withProperty("mars.gateway.proxy.direct-peer-cidrs", cidrs)
                .withProperty("server.forward-headers-strategy", "none");
        environment.setActiveProfiles("test");
        return environment;
    }

    private static MockServerHttpRequest.BaseBuilder<?> request(String peer) {
        return MockServerHttpRequest.get("http://localhost:8100/auth/v1/me")
                .remoteAddress(new InetSocketAddress(peer, 51600))
                .localAddress(new InetSocketAddress("127.0.0.1", 8100))
                .header("Host", "auth.flippoabc.com");
    }

    private static ServerWebExchange forward(GatewayIngressFilter filter, MockServerHttpRequest request) {
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
        filter.filter(MockServerWebExchange.from(request), exchange -> {
            passed.set(exchange);
            return Mono.empty();
        }).block();
        return passed.get();
    }

    @Test
    void directModeUsesTcpPeerAndRemovesEveryExternalIdentityAndForwardingHeader() {
        var filter = new GatewayIngressFilter(environment(0, ""));
        var exchange = forward(filter, request("192.0.2.10")
                .header("X-Forwarded-For", "198.51.100.1", "198.51.100.2")
                .header("Forwarded", "for=198.51.100.1")
                .header("X-Forwarded-Custom", "spoof")
                .header("X-Mars-Subject", "spoof", "spoof2")
                .header("X-Mars-Client-Id", "spoof")
                .header("X-Mars-Tenant-Id", "spoof")
                .build());
        var headers = exchange.getRequest().getHeaders();
        assertEquals("192.0.2.10", exchange.getAttribute(GatewayIngressFilter.CLIENT_IP_ATTRIBUTE));
        assertEquals("192.0.2.10", headers.getFirst("X-Forwarded-For"));
        assertEquals(1, headers.getOrEmpty("X-Forwarded-For").size());
        assertEquals("auth.flippoabc.com", headers.getFirst("X-Forwarded-Host"));
        assertEquals("http", headers.getFirst("X-Forwarded-Proto"));
        assertEquals("80", headers.getFirst("X-Forwarded-Port"));
        for (String name : new String[]{"Forwarded", "X-Forwarded-Custom", "X-Mars-Subject", "X-Mars-Client-Id", "X-Mars-Tenant-Id"})
            assertTrue(headers.getOrEmpty(name).isEmpty(), name);
    }

    @Test
    void trustedModeSelectsNthAddressFromRightAndDiscardsSpoofedLeftEntry() {
        var filter = new GatewayIngressFilter(environment(2, "10.0.0.0/24"));
        var exchange = forward(filter, request("10.0.0.3")
                .header("X-Forwarded-For", "198.51.100.77, 192.0.2.23, 10.0.0.2")
                .header("X-Forwarded-Proto", "https")
                .build());
        assertEquals("192.0.2.23", exchange.getAttribute(GatewayIngressFilter.CLIENT_IP_ATTRIBUTE));
        assertEquals("192.0.2.23", exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"));
        assertEquals("https", exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto"));
    }

    @Test
    void trustedModeRejectsUntrustedPeerAndInvalidChains() {
        var filter = new GatewayIngressFilter(environment(2, "10.0.0.0/24"));
        assertStatus(filter, request("10.0.1.3").header("X-Forwarded-For", "192.0.2.1, 10.0.0.2")
                .header("X-Forwarded-Proto", "https").build(), HttpStatus.FORBIDDEN);
        assertStatus(filter, request("10.0.0.3").header("X-Forwarded-Proto", "https").build(), HttpStatus.BAD_REQUEST);
        assertStatus(filter, request("10.0.0.3").header("X-Forwarded-For", "192.0.2.1")
                .header("X-Forwarded-Proto", "https").build(), HttpStatus.BAD_REQUEST);
        assertStatus(filter, request("10.0.0.3").header("X-Forwarded-For", "192.0.2.1, 10.0.0.2", "1.1.1.1")
                .header("X-Forwarded-Proto", "https").build(), HttpStatus.BAD_REQUEST);
        assertStatus(filter, request("10.0.0.3").header("X-Forwarded-For", "example.com, 10.0.0.2")
                .header("X-Forwarded-Proto", "https").build(), HttpStatus.BAD_REQUEST);
        assertStatus(filter, request("10.0.0.3").header("X-Forwarded-For", "192.0.2.1, 10.0.0.2")
                .header("X-Forwarded-Proto", "https", "http").build(), HttpStatus.BAD_REQUEST);
    }

    @Test
    void malformedProxyConfigurationFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> new GatewayIngressFilter(environment(1, "")));
        assertThrows(IllegalStateException.class, () -> new GatewayIngressFilter(environment(-1, "10.0.0.0/24")));
        assertThrows(IllegalStateException.class, () -> new GatewayIngressFilter(environment(1, "example.com/24")));
    }

    private static void assertStatus(GatewayIngressFilter filter, MockServerHttpRequest request, HttpStatus expected) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> filter.filter(MockServerWebExchange.from(request), exchange -> Mono.empty()).block());
        assertEquals(expected, ex.getStatusCode());
    }
}
