package com.mars.cloud.service.gateway.web;

import org.springframework.core.env.Environment;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** 网关业务端口接受的 Host 与本地测试条件。 */
final class GatewayHostPolicy {

    private static final Set<String> LOCAL_PROFILES = Set.of("local", "test");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private GatewayHostPolicy() {
    }

    static boolean isLocalTest(Environment environment) {
        String[] active = environment.getActiveProfiles();
        return active.length > 0 && Arrays.stream(active).allMatch(LOCAL_PROFILES::contains);
    }

    static boolean isApiHost(ServerHttpRequest request, boolean localTest) {
        Host host = host(request);
        return host != null && (isPublicHost(host, "api.flippoabc.com")
                || (localTest && isLoopbackHost(request, host)));
    }

    static boolean isAuthHost(ServerHttpRequest request, boolean localTest) {
        Host host = host(request);
        return host != null && (isPublicHost(host, "auth.flippoabc.com")
                || (localTest && isLoopbackHost(request, host)));
    }

    private static boolean isPublicHost(Host host, String expected) {
        return host.name().equals(expected) && (host.port() == -1 || host.port() == 443);
    }

    private static boolean isLoopbackHost(ServerHttpRequest request, Host host) {
        InetSocketAddress local = request.getLocalAddress();
        return local != null && local.getAddress() != null && local.getAddress().isLoopbackAddress()
                && LOOPBACK_HOSTS.contains(host.name())
                && (host.port() == -1 || host.port() == local.getPort());
    }

    private static Host host(ServerHttpRequest request) {
        var values = request.getHeaders().getOrEmpty("Host");
        if (values.size() != 1) {
            return null;
        }
        String raw = values.getFirst();
        if (raw == null || raw.isBlank() || raw.chars().anyMatch(c -> Character.isWhitespace(c)
                || c == ',' || c == '/' || c == '@' || c == '?' || c == '#' || c == '%')) {
            return null;
        }
        try {
            URI uri = URI.create("http://" + raw);
            if (uri.getHost() == null || uri.getRawUserInfo() != null
                    || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return null;
            }
            return new Host(uri.getHost().toLowerCase(Locale.ROOT), uri.getPort());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private record Host(String name, int port) {
    }
}
