package com.mars.cloud.service.auth.configuration;

import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import com.mars.cloud.service.auth.domain.ClientPolicy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Validates configuration before database migrations or authentication are installed. */
@Component
public final class AuthEnvironment {
    private final boolean local;
    public AuthEnvironment(AuthProperties properties, Environment environment) {
        var profiles = Arrays.asList(environment.getActiveProfiles());
        local = !profiles.isEmpty() && profiles.stream().allMatch(p -> p.equals("local") || p.equals("test"));
        URI issuer;
        try { issuer = URI.create(properties.getIssuer()); }
        catch (RuntimeException ex) { throw new IllegalStateException("An explicit HTTPS issuer is required"); }
        boolean loopback = "127.0.0.1".equals(issuer.getHost());
        int port = environment.getProperty("server.port", Integer.class, 8101);
        if (!"https".equals(issuer.getScheme()) || issuer.getHost() == null || issuer.getUserInfo() != null
                || issuer.getRawQuery() != null || issuer.getFragment() != null || !issuer.getRawPath().isEmpty()
                || (local && (!loopback || issuer.getPort() != port))
                || (!local && !ClientPolicy.formalHost(issuer.getHost())))
            throw new IllegalStateException("Issuer does not match the environment");
        if (local && !environment.getProperty("server.ssl.enabled",Boolean.class,false))
            throw new IllegalStateException("Local authentication requires an HTTPS listener");
        if (!java.util.Set.of("", "/").contains(environment.getProperty("server.servlet.context-path", "")))
            throw new IllegalStateException("Authentication protocol endpoints require the root context path");
        if (!"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "none")))
            throw new IllegalStateException("Forwarded headers must remain disabled in the container; trusted gateways use the request filter");
        if (properties.getLocalLogin().isEnabled() && !local)
            throw new IllegalStateException("Local login is restricted to local/test profiles");
        var sms = properties.getSms();
        if (sms.getDailyBudget() <= 0 || sms.getCaptchaErrorThreshold() <= 0 || sms.getCaptchaErrorThreshold() > 5)
            throw new IllegalStateException("SMS limits must be positive and within the challenge attempt limit");
        if (sms.isMockEnabled() && (!local || sms.getMockOutbox() == null || sms.getMockOutbox().isBlank()))
            throw new IllegalStateException("Mock SMS requires local/test and an explicit outbox");
        if (sms.isMockEnabled() || (sms.getHmacKey() != null && !sms.getHmacKey().isBlank())) {
            try {
                if (Base64.getDecoder().decode(sms.getHmacKey()).length != 32) throw new IllegalArgumentException();
            } catch (RuntimeException ex) { throw new IllegalStateException("SMS requires a separate 256-bit HMAC key"); }
        }
        try {
            if (Base64.getDecoder().decode(properties.getJwk().getEncryptionKey()).length != 32)
                throw new IllegalArgumentException();
        } catch (RuntimeException ex) { throw new IllegalStateException("A 256-bit signing-key encryption key is required"); }
        if (properties.getJwk().getEncryptionKeyId() == null || !properties.getJwk().getEncryptionKeyId().matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalStateException("A signing-key encryption version is required");
        if (!properties.getClients().keySet().containsAll(ClientPolicy.FORMAL))
            throw new IllegalStateException("All six clients require deployment configuration");
        properties.getClients().forEach((id, client) ->
                ClientPolicy.create(id, client.getRedirectUris(), client.getPostLogoutRedirectUris(), local));
        if (properties.getLocalLogin().isEnabled()) {
            var login = properties.getLocalLogin();
            if (login.getUserId() == null || !login.getUserId().matches("[1-9][0-9]{0,17}")
                    || login.getPassword() == null || login.getPassword().length() < 24 || login.getPassword().length() > 72)
                throw new IllegalStateException("Local login requires an explicit numeric user ID and random password");
        }
    }
    public boolean local() { return local; }
}
