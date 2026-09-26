package com.mars.cloud.service.auth.application;

import static com.mars.cloud.service.auth.application.DeviceTestSupport.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mars.cloud.service.auth.domain.RevokeReason;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.session.MapSessionRepository;

/** The standard refresh grant provider must stop honouring a refresh token once its device session is revoked. */
class SessionRevocationTest {
    @AfterEach void clearContext() { AuthorizationServerContextHolder.resetContext(); }

    @Test void revokedNativeDeviceCannotRefreshItsTokens() throws Exception {
        var source = DeviceTestSupport.dataSource();
        var jdbc = new JdbcTemplate(source);
        var client = DeviceTestSupport.nativeClient();
        var store = new JdbcOAuth2AuthorizationService(jdbc, new InMemoryRegisteredClientRepository(client));
        String deviceId = "native-device-1";
        store.save(DeviceTestSupport.authorization(client, deviceId, "refresh-1", Set.of("device")));
        DeviceTestSupport.row(jdbc, deviceId, "NATIVE", client.getClientId(), START, null, null);
        var markers = new ArrayList<String>();
        var devices = new DeviceSessionService(jdbc, new DataSourceTransactionManager(source), new DeviceTestSupport.TestClock(),
                new MapSessionRepository(new ConcurrentHashMap<>()), () -> store, markers::add);

        var pair = KeyPairGenerator.getInstance("RSA");
        pair.initialize(2048);
        var keys = pair.generateKeyPair();
        var jwk = new RSAKey.Builder((RSAPublicKey) keys.getPublic()).privateKey((RSAPrivateKey) keys.getPrivate()).keyID("test").build();
        var generator = new DelegatingOAuth2TokenGenerator(new JwtGenerator(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)))), new OAuth2RefreshTokenGenerator());
        var settings = AuthorizationServerSettings.builder().issuer("https://auth.test").build();
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override public String getIssuer() { return settings.getIssuer(); }
            @Override public AuthorizationServerSettings getAuthorizationServerSettings() { return settings; }
        });
        var provider = new OAuth2RefreshTokenAuthenticationProvider(store, generator);
        var principal = new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);

        var rotated = (OAuth2AccessTokenAuthenticationToken) provider.authenticate(new OAuth2RefreshTokenAuthenticationToken("refresh-1", principal, Set.of(), Map.of()));
        assertThat(rotated.getRefreshToken()).isNotNull();
        String current = rotated.getRefreshToken().getTokenValue();
        assertThat(current).isNotEqualTo("refresh-1");
        assertThat(store.findByToken(current, OAuth2TokenType.REFRESH_TOKEN)).isNotNull();

        assertThat(devices.revoke(deviceId, RevokeReason.DEVICE_LIMIT)).isTrue();
        assertThat(markers).containsExactly(deviceId);
        assertThat(store.findById(deviceId)).isNull();
        assertThat(store.findByToken(current, OAuth2TokenType.REFRESH_TOKEN)).isNull();
        assertThatThrownBy(() -> provider.authenticate(new OAuth2RefreshTokenAuthenticationToken(current, principal, Set.of(), Map.of())))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, failure -> assertThat(failure.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));
        List<String> reasons = jdbc.queryForList("SELECT revoke_reason FROM sys_session WHERE session_id=?", String.class, deviceId);
        assertThat(reasons).containsExactly("DEVICE_LIMIT");
    }
}
