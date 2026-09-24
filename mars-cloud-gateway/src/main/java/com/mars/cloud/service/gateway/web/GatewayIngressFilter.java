package com.mars.cloud.service.gateway.web;

import com.mars.cloud.common.context.InternalCallHeaders;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 在跨域和路由处理前确定来源地址，并清除外部伪造的内部头与转发头。 */
@Component
public final class GatewayIngressFilter implements WebFilter, Ordered {
    public static final String CLIENT_IP_ATTRIBUTE = GatewayIngressFilter.class.getName() + ".clientIp";
    static final String CANONICAL_HEADERS_ATTRIBUTE = GatewayIngressFilter.class.getName() + ".canonicalHeaders";
    private final int trustedHopCount;
    private final List<IpAddressPolicy.Subnet> directPeers;
    private final boolean localTest;
    private final int managementPort;

    public GatewayIngressFilter(Environment environment) {
        trustedHopCount = environment.getProperty("mars.gateway.proxy.trusted-hop-count", Integer.class, 0);
        if (trustedHopCount < 0) throw new IllegalStateException("Trusted hop count must be nonnegative");
        try {
            directPeers = IpAddressPolicy.cidrs(environment.getProperty("mars.gateway.proxy.direct-peer-cidrs", ""));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid direct peer CIDRs", ex);
        }
        if (trustedHopCount > 0 && directPeers.isEmpty())
            throw new IllegalStateException("Trusted proxy mode requires direct peer CIDRs");
        localTest = GatewayHostPolicy.isLocalTest(environment);
        managementPort = environment.getProperty("management.server.port", Integer.class, -1);
        if (!"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "none")))
            throw new IllegalStateException("Gateway forwarding strategy must remain none");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        InetSocketAddress local = request.getLocalAddress();
        if (local != null && local.getPort() == managementPort) return chain.filter(exchange);
        try {
            InetSocketAddress remote = request.getRemoteAddress();
            if (remote == null || remote.getAddress() == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing TCP peer address");
            InetAddress client = remote.getAddress();
            HttpHeaders inbound = request.getHeaders();
            if (trustedHopCount > 0) {
                boolean trusted = false;
                for (IpAddressPolicy.Subnet cidr : directPeers) trusted |= cidr.contains(client);
                if (!trusted) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Untrusted direct peer");
                List<String> chains = inbound.getOrEmpty("X-Forwarded-For");
                if (chains.size() != 1) throw badForwarding();
                String[] addresses = chains.getFirst().split(",", -1);
                if (addresses.length < trustedHopCount) throw badForwarding();
                List<InetAddress> parsed = new ArrayList<>();
                for (String address : addresses) parsed.add(IpAddressPolicy.literal(address.trim()));
                client = parsed.get(parsed.size() - trustedHopCount);
            }
            String scheme = trustedHopCount == 0 ? request.getURI().getScheme() : single(inbound, "X-Forwarded-Proto");
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || (trustedHopCount > 0 && !localTest && !scheme.equalsIgnoreCase("https"))) throw badForwarding();
            scheme = scheme.toLowerCase(Locale.ROOT);
            String host = GatewayHostPolicy.canonicalHost(request);
            if (host == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Host");
            String selected = client.getHostAddress();
            String canonicalScheme = scheme;
            ServerHttpRequest clean = request.mutate().headers(headers -> {
                for (String name : new ArrayList<>(headers.headerNames())) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.equals("forwarded") || lower.startsWith("x-forwarded-")
                            || name.equalsIgnoreCase(InternalCallHeaders.SUBJECT)
                            || name.equalsIgnoreCase(InternalCallHeaders.CLIENT_ID)
                            || name.equalsIgnoreCase(InternalCallHeaders.TENANT_ID)) headers.remove(name);
                }
                headers.set("X-Forwarded-For", selected);
                headers.set("X-Forwarded-Host", host);
                headers.set("X-Forwarded-Proto", canonicalScheme);
                int colon = host.lastIndexOf(':');
                String port = colon >= 0 && host.indexOf(']') < colon ? host.substring(colon + 1)
                        : (canonicalScheme.equals("https") ? "443" : "80");
                headers.set("X-Forwarded-Port", port);
            }).build();
            exchange.getAttributes().put(CLIENT_IP_ATTRIBUTE, selected);
            exchange.getAttributes().put(CANONICAL_HEADERS_ATTRIBUTE,
                    new CanonicalHeaders(selected, host, canonicalScheme, clean.getHeaders().getFirst("X-Forwarded-Port")));
            return chain.filter(exchange.mutate().request(clean).build());
        } catch (IllegalArgumentException ex) {
            return Mono.error(badForwarding());
        } catch (ResponseStatusException ex) {
            return Mono.error(ex);
        }
    }

    private static String single(HttpHeaders headers, String name) {
        List<String> values = headers.getOrEmpty(name);
        return values.size() == 1 ? values.getFirst() : null;
    }

    private static ResponseStatusException badForwarding() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid forwarding headers");
    }

    record CanonicalHeaders(String clientIp, String host, String scheme, String port) { }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
