package com.mars.cloud.service.gateway.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class GatewayAdminIpAllowlistTest {
    @Test
    void validUpdatesReplaceTheWholePolicyAndInvalidUpdatesRetainThePreviousPolicy() {
        var environment = new MockEnvironment().withProperty(GatewayAdminIpAllowlist.PROPERTY, "192.0.2.0/24");
        var allowlist = new GatewayAdminIpAllowlist(environment);
        assertTrue(allowlist.allows("192.0.2.10"));
        assertFalse(allowlist.allows("198.51.100.10"));

        var logger = (Logger) LoggerFactory.getLogger(GatewayAdminIpAllowlist.class);
        var warnings = new ListAppender<ILoggingEvent>();
        warnings.start();
        boolean additive = logger.isAdditive();
        logger.addAppender(warnings);
        logger.setAdditive(false);
        try {
            environment.setProperty(GatewayAdminIpAllowlist.PROPERTY, "198.51.100.0/24,example.com");
            allowlist.refresh(new EnvironmentChangeEvent(Set.of(GatewayAdminIpAllowlist.PROPERTY)));
            assertEquals(1, warnings.list.size());
            assertTrue(warnings.list.getFirst().getFormattedMessage().contains("previous policy remains active"));
        } finally {
            logger.detachAppender(warnings);
            logger.setAdditive(additive);
            warnings.stop();
        }
        assertTrue(allowlist.allows("192.0.2.10"));
        assertFalse(allowlist.allows("198.51.100.10"));

        environment.setProperty(GatewayAdminIpAllowlist.PROPERTY, "198.51.100.0/24");
        allowlist.refresh(new EnvironmentChangeEvent(Set.of(GatewayAdminIpAllowlist.PROPERTY)));
        assertFalse(allowlist.allows("192.0.2.10"));
        assertTrue(allowlist.allows("198.51.100.10"));

        environment.setProperty(GatewayAdminIpAllowlist.PROPERTY, "");
        allowlist.refresh(new EnvironmentChangeEvent(Set.of(GatewayAdminIpAllowlist.PROPERTY)));
        assertFalse(allowlist.allows("198.51.100.10"));
        assertThrows(IllegalStateException.class, () -> new GatewayAdminIpAllowlist(
                new MockEnvironment().withProperty(GatewayAdminIpAllowlist.PROPERTY, "host.invalid")));
    }

    @Test
    void canonicalIpComesFromTcpPeerOrVerifiedProxyHop() {
        var allowlist = new GatewayAdminIpAllowlist(
                new MockEnvironment().withProperty(GatewayAdminIpAllowlist.PROPERTY, "192.0.2.10/32"));
        var direct = ingress(0, "", "198.51.100.3", "192.0.2.10", null);
        assertFalse(allowlist.allows(direct.getAttribute(GatewayIngressFilter.CLIENT_IP_ATTRIBUTE)));

        var proxied = ingress(1, "10.0.0.0/24", "10.0.0.3", "192.0.2.10", "https");
        assertTrue(allowlist.allows(proxied.getAttribute(GatewayIngressFilter.CLIENT_IP_ATTRIBUTE)));
    }

    private static ServerWebExchange ingress(int hops, String peers, String remote, String forwarded, String proto) {
        var environment = new MockEnvironment()
                .withProperty("mars.gateway.proxy.trusted-hop-count", Integer.toString(hops))
                .withProperty("mars.gateway.proxy.direct-peer-cidrs", peers)
                .withProperty("server.forward-headers-strategy", "none");
        environment.setActiveProfiles("test");
        var request = MockServerHttpRequest.get("http://localhost:8100/product/v1/admin/items")
                .remoteAddress(new InetSocketAddress(remote, 50000))
                .localAddress(new InetSocketAddress("127.0.0.1", 8100))
                .header("Host", "api.flippoabc.com")
                .header("X-Forwarded-For", forwarded);
        if (proto != null) request.header("X-Forwarded-Proto", proto);
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
        new GatewayIngressFilter(environment).filter(MockServerWebExchange.from(request.build()), exchange -> {
            passed.set(exchange);
            return Mono.empty();
        }).block();
        return passed.get();
    }
}
