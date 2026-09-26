package com.mars.cloud.service.auth.application;

import com.mars.cloud.service.auth.domain.ClientPolicy;
import com.mars.cloud.service.auth.infrastructure.authorization.SessionAuthorizationService;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/** In-memory database with the session tables and the protocol schema; no middleware. */
final class DeviceTestSupport {
    static final long USER = 10001L;
    static final Instant START = Instant.parse("2026-09-26T10:00:00Z");
    private DeviceTestSupport() { }

    /** Clock whose instant the test moves forward explicitly. */
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(START);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
        void set(Instant instant) { now.set(instant); }
    }

    static DriverManagerDataSource dataSource() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_user (user_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE sys_session (session_id VARCHAR(100) PRIMARY KEY, user_id BIGINT NOT NULL, client_id VARCHAR(100), kind VARCHAR(16) NOT NULL,"
                + " device_label VARCHAR(200), ip VARCHAR(45), created_at TIMESTAMP(6) NOT NULL, last_seen_at TIMESTAMP(6) NOT NULL, revoked_at TIMESTAMP(6), revoke_reason VARCHAR(64))");
        jdbc.execute("CREATE TABLE sys_login_log (log_id BIGINT PRIMARY KEY, user_id BIGINT, event VARCHAR(64) NOT NULL, result VARCHAR(32) NOT NULL, channel VARCHAR(64),"
                + " client_id VARCHAR(100), ip VARCHAR(45), user_agent VARCHAR(512), created_at TIMESTAMP(6) NOT NULL, details CLOB)");
        jdbc.update("INSERT INTO sys_user (user_id) VALUES (?)", USER);
        return source;
    }

    static RegisteredClient nativeClient() {
        return ClientPolicy.create("test-native", List.of("http://127.0.0.1:8309/callback"), List.of("http://127.0.0.1:8309/logged-out"), true);
    }

    /** The authenticated resource owner that the protocol providers read from a stored authorization. */
    static UsernamePasswordAuthenticationToken resourceOwner() {
        var user = User.withUsername(Long.toString(USER)).password("unused").authorities("LOCAL_LOGIN").build();
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }

    static void row(JdbcTemplate jdbc, String id, String kind, String clientId, Instant lastSeen, Instant revokedAt, String reason) {
        jdbc.update("INSERT INTO sys_session (session_id,user_id,client_id,kind,device_label,ip,created_at,last_seen_at,revoked_at,revoke_reason) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, USER, clientId, kind, "test agent", "198.51.100.7", Timestamp.from(lastSeen), Timestamp.from(lastSeen),
                revokedAt == null ? null : Timestamp.from(revokedAt), reason);
    }

    /** A native authorization with live tokens whose id is the device session identifier. */
    static OAuth2Authorization authorization(RegisteredClient client, String id, String refreshTokenValue, Set<String> scopes) {
        Instant now = Instant.now();
        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-" + id, now.minusSeconds(60), now.plusSeconds(600), scopes);
        var builder = OAuth2Authorization.withRegisteredClient(client).id(id).principalName(Long.toString(USER))
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizedScopes(scopes)
                .attribute(SessionAuthorizationService.SID, id).attribute(Principal.class.getName(), resourceOwner()).accessToken(access);
        if (refreshTokenValue != null) builder.refreshToken(new OAuth2RefreshToken(refreshTokenValue, now, now.plusSeconds(3600)));
        return builder.build();
    }
}
