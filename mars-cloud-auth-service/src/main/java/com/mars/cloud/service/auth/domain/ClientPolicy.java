package com.mars.cloud.service.auth.domain;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

public final class ClientPolicy {
    public static final Set<String> FORMAL = Set.of("portal", "flippo-book", "wonder-lab",
            "english-word-card", "console", "csthink-assistant");
    public static final Set<String> TEST = Set.of("test-browser", "test-native");
    private ClientPolicy() { }
    public static boolean nativeClient(String id) {
        return "csthink-assistant".equals(id) || "test-native".equals(id);
    }
    public static List<String> audiences(String id) {
        List<String> names = switch (id) {
            case "portal" -> List.of("gateway", "auth-service", "product-service", "order-service", "notice-service", "support-service");
            case "console" -> List.of("gateway", "auth-service", "product-service", "order-service", "notice-service", "upms-service");
            case "flippo-book", "wonder-lab", "english-word-card", "csthink-assistant", "test-browser", "test-native" ->
                    List.of("gateway", "auth-service", "product-service", "notice-service");
            default -> throw new IllegalArgumentException("Unknown client");
        };
        return names.stream().map(name -> "mars-cloud-" + name).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }
    public static RegisteredClient create(String id, List<String> redirects, List<String> logouts, boolean local) {
        if (!FORMAL.contains(id) && !(local && TEST.contains(id))) throw new IllegalArgumentException("Unregistered client");
        validateUris(id, redirects, local);
        validateUris(id, logouts, local);
        var builder = RegisteredClient.withId(id).clientId(id).clientName(id)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope("openid")
                .redirectUris(values -> values.addAll(redirects))
                .postLogoutRedirectUris(values -> values.addAll(logouts))
                .clientSettings(ClientSettings.builder().requireProofKey(true)
                        .requireAuthorizationConsent(nativeClient(id)).build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(30)).reuseRefreshTokens(false).build());
        if (nativeClient(id)) builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        return builder.build();
    }
    public static boolean formalHost(String host) {
        if (host == null || host.length() > 253 || host.endsWith(".") || host.contains(":")) return false;
        String value = host.toLowerCase(java.util.Locale.ROOT);
        return value.contains(".") && !value.endsWith(".localhost")
                && !value.matches("[0-9.]+")
                && value.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    }
    public static void validateUris(String id, List<String> values, boolean local) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("Client redirect configuration is required");
        for (String value : values) {
            URI uri = URI.create(value);
            boolean loopback = "127.0.0.1".equals(uri.getHost());
            boolean loopbackRequired = nativeClient(id) || TEST.contains(id);
            if (value.contains("*") || uri.getUserInfo() != null || uri.getFragment() != null || uri.getHost() == null
                    || !value.equals(value.strip()) || uri.getRawPath().isEmpty()
                    || (loopbackRequired && (!loopback || uri.getPort() < 1))
                    || (!loopbackRequired && !formalHost(uri.getHost()))
                    || !("https".equals(uri.getScheme()) || (loopbackRequired && "http".equals(uri.getScheme()))))
                throw new IllegalArgumentException("Invalid client redirect configuration");
        }
    }
}
