package com.mars.cloud.service.gateway.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 仅对 API Host 的已声明路径提供跨域策略。 */
@Configuration(proxyBeanMethods = false)
public class ApiCorsConfiguration {

    private static final List<String> PUBLIC_ORIGINS = List.of(
            "https://flippoabc.com",
            "https://word.flippoabc.com",
            "https://console.flippoabc.com");
    private static final Set<String> API_PREFIXES = Set.of(
            "/auth", "/product", "/order", "/notice", "/upms", "/sample");

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    CorsWebFilter apiCorsWebFilter(Environment environment) {
        boolean localTest = GatewayHostPolicy.isLocalTest(environment);
        List<String> allowedOrigins = new ArrayList<>(PUBLIC_ORIGINS);
        String localOrigins = environment.getProperty("mars.gateway.cors.local-origins", "");
        if (!localOrigins.isBlank()) {
            if (!localTest) {
                throw new IllegalStateException("Local CORS origins require local or test profile");
            }
            for (String value : localOrigins.split(",", -1)) {
                String origin = value.trim();
                if (!isLoopbackOrigin(origin)) {
                    throw new IllegalStateException("Local CORS origin must be a complete loopback origin");
                }
                allowedOrigins.add(origin);
            }
        }

        CorsConfiguration policy = new CorsConfiguration();
        policy.setAllowedOrigins(allowedOrigins);
        policy.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        policy.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key"));
        policy.setAllowCredentials(false);
        policy.setMaxAge(3600L);

        return new CorsWebFilter(exchange -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getPath().pathWithinApplication().value();
            if (!GatewayHostPolicy.isApiHost(request, localTest) || !isApiPath(path)
                    || (!localTest && (path.equals("/upms") || path.startsWith("/upms/")
                    || path.equals("/sample") || path.startsWith("/sample/")))) {
                return null;
            }
            return policy;
        });
    }

    private static boolean isApiPath(String path) {
        return API_PREFIXES.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private static boolean isLoopbackOrigin(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            return scheme != null && Set.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT))
                    && host != null
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(host.toLowerCase(Locale.ROOT))
                    && uri.getPort() > 0 && uri.getPort() <= 65535
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
