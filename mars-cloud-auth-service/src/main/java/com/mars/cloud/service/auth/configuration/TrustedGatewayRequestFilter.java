package com.mars.cloud.service.auth.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 仅对指定网关连接采用网关生成的外部请求信息。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TrustedGatewayRequestFilter extends OncePerRequestFilter {
    private final List<IpAddressPolicy.Subnet> trustedGateways;
    private final boolean localTest;
    private final int managementPort;

    public TrustedGatewayRequestFilter(Environment environment) {
        localTest = Arrays.stream(environment.getActiveProfiles()).allMatch(Set.of("local", "test")::contains)
                && environment.getActiveProfiles().length > 0;
        try {
            trustedGateways = IpAddressPolicy.cidrs(environment.getProperty("mars.auth.trusted-gateway-cidrs", ""));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid trusted gateway CIDRs", ex);
        }
        if (!localTest && trustedGateways.isEmpty())
            throw new IllegalStateException("Formal authentication requires trusted gateway CIDRs");
        managementPort = environment.getProperty("management.server.port", Integer.class, -1);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getLocalPort() == managementPort) {
            chain.doFilter(request, response);
            return;
        }
        External external = null;
        try {
            InetAddress peer = IpAddressPolicy.literal(request.getRemoteAddr());
            boolean trusted = false;
            for (IpAddressPolicy.Subnet cidr : trustedGateways) trusted |= cidr.contains(peer);
            if (trusted) {
                rejectUnexpectedForwardingHeaders(request);
                String client = IpAddressPolicy.literal(single(request, "X-Forwarded-For")).getHostAddress();
                String scheme = single(request, "X-Forwarded-Proto").toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("Invalid protocol");
                if (!localTest && !scheme.equals("https")) throw new IllegalArgumentException("Formal protocol requires HTTPS");
                String rawHost = single(request, "X-Forwarded-Host");
                Host host = Host.parse(rawHost);
                int port = Integer.parseInt(single(request, "X-Forwarded-Port"));
                if (port < 1 || port > 65535 || port != (host.port() < 0 ? (scheme.equals("https") ? 443 : 80) : host.port()))
                    throw new IllegalArgumentException("Invalid forwarded port");
                external = new External(client, scheme, host.name(), port, rawHost);
            }
        } catch (IllegalArgumentException ex) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid gateway forwarding headers");
            return;
        }
        chain.doFilter(new ExternalRequest(request, external), response);
    }

    private static void rejectUnexpectedForwardingHeaders(HttpServletRequest request) {
        Set<String> canonical = Set.of("x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port");
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("forwarded") || (lower.startsWith("x-forwarded-") && !canonical.contains(lower)))
                throw new IllegalArgumentException("Unexpected forwarding header");
        }
    }

    private static String single(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank())
            throw new IllegalArgumentException("Missing or repeated forwarding header");
        return values.getFirst();
    }

    private record Host(String name, int port) {
        static Host parse(String raw) {
            if (raw == null || raw.isBlank() || raw.endsWith(":") || raw.chars().anyMatch(c -> Character.isWhitespace(c)
                    || c == ',' || c == '/' || c == '@' || c == '?' || c == '#' || c == '%'))
                throw new IllegalArgumentException("Invalid forwarded host");
            URI uri = URI.create("http://" + raw);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException("Invalid forwarded host");
            return new Host(uri.getHost().toLowerCase(Locale.ROOT), uri.getPort());
        }
    }

    private record External(String client, String scheme, String host, int port, String rawHost) { }

    /** 直接请求隐藏转发头；可信请求只提供经过校验的外部地址与协议。 */
    private static final class ExternalRequest extends HttpServletRequestWrapper {
        private final External external;
        ExternalRequest(HttpServletRequest request, External external) { super(request); this.external = external; }
        @Override public String getRemoteAddr() { return external == null ? super.getRemoteAddr() : external.client(); }
        @Override public String getRemoteHost() { return getRemoteAddr(); }
        @Override public String getScheme() { return external == null ? super.getScheme() : external.scheme(); }
        @Override public boolean isSecure() { return external == null ? super.isSecure() : external.scheme().equals("https"); }
        @Override public String getServerName() { return external == null ? super.getServerName() : external.host(); }
        @Override public int getServerPort() { return external == null ? super.getServerPort() : external.port(); }
        @Override public StringBuffer getRequestURL() {
            if (external == null) return super.getRequestURL();
            String authority = external.rawHost();
            return new StringBuffer(external.scheme() + "://" + authority + getRequestURI());
        }
        @Override public String getHeader(String name) {
            if (external == null && forwarded(name)) return null;
            if (external != null && name.equalsIgnoreCase("Host")) return external.rawHost();
            return super.getHeader(name);
        }
        @Override public Enumeration<String> getHeaders(String name) {
            if (external == null && forwarded(name)) return Collections.emptyEnumeration();
            if (external != null && name.equalsIgnoreCase("Host")) return Collections.enumeration(List.of(external.rawHost()));
            return super.getHeaders(name);
        }
        @Override public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> external != null || !forwarded(name)).toList());
        }
        private static boolean forwarded(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.equals("forwarded") || lower.startsWith("x-forwarded-");
        }
    }
}
