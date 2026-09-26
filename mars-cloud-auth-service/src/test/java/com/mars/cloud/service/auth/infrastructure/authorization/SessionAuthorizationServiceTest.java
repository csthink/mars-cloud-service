package com.mars.cloud.service.auth.infrastructure.authorization;

import static com.mars.cloud.service.auth.application.DeviceTestSupportAccess.START;
import static com.mars.cloud.service.auth.application.DeviceTestSupportAccess.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mars.cloud.service.auth.application.AuthSessionService;
import com.mars.cloud.service.auth.application.DeviceSessionService;
import com.mars.cloud.service.auth.application.DeviceTestSupportAccess;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The decorator registers the native device under the authorization id and records activity only for live tokens. */
class SessionAuthorizationServiceTest {
    private JdbcTemplate jdbc;
    private JdbcOAuth2AuthorizationService store;
    private RegisteredClient client;
    private AuthSessionService sessions;
    private DataSourceTransactionManager manager;
    private final DeviceTestSupportAccess.TestClock clock = new DeviceTestSupportAccess.TestClock();
    private final List<String> markers = new ArrayList<>();
    private final MockServletContext context = new MockServletContext();

    @BeforeEach void setUp() {
        var source = DeviceTestSupportAccess.dataSource();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        client = DeviceTestSupportAccess.nativeClient();
        store = new JdbcOAuth2AuthorizationService(jdbc, new InMemoryRegisteredClientRepository(client));
        var repository = new MapSessionRepository(new ConcurrentHashMap<>());
        var devices = new DeviceSessionService(jdbc, manager, clock, repository, () -> store, markers::add);
        sessions = new AuthSessionService(jdbc, clock, devices);
        MapSession stored = repository.createSession();
        repository.save(stored);
        var browser = new MockHttpServletRequest(context);
        browser.setSession(new MockHttpSession(context, stored.getId()));
        browser.setRemoteAddr("198.51.100.40");
        sessions.login(Long.toString(USER), browser);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(browser));
    }

    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    private OAuth2Authorization.Builder pending(String id) {
        Instant now = Instant.now();
        return OAuth2Authorization.withRegisteredClient(client).id(id).principalName(Long.toString(USER))
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizedScopes(Set.of("openid"))
                .attribute(Principal.class.getName(), DeviceTestSupportAccess.resourceOwner())
                .token(new OAuth2AuthorizationCode("code-" + id, now, now.plusSeconds(300)));
    }

    private Timestamp lastSeen(String id) { return jdbc.queryForObject("SELECT last_seen_at FROM sys_session WHERE session_id=?", Timestamp.class, id); }

    @Test void issuingACodeRegistersTheNativeDeviceUnderTheAuthorizationId() {
        var service = new SessionAuthorizationService(store, sessions, new InMemoryRegisteredClientRepository(client), manager);
        service.save(pending("authorization-7").build());
        OAuth2Authorization saved = store.findById("authorization-7");
        assertThat(saved).isNotNull();
        assertThat(saved.<String>getAttribute(SessionAuthorizationService.SID)).isEqualTo("authorization-7");
        assertThat(jdbc.queryForMap("SELECT kind,client_id FROM sys_session WHERE session_id='authorization-7'")).containsEntry("KIND", "NATIVE").containsEntry("CLIENT_ID", "test-native");
        assertThat(store.findByToken("code-authorization-7", new OAuth2TokenType("code"))).isNotNull();
    }

    @Test void onlyALiveAccessTokenRefreshesTheNativeDevice() {
        var service = new SessionAuthorizationService(store, sessions, new InMemoryRegisteredClientRepository(client), manager);
        service.save(pending("authorization-8").build());
        Instant now = Instant.now();
        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-8", now, now.plusSeconds(600), Set.of("openid"));
        clock.set(START.plus(Duration.ofMinutes(3)));
        service.save(OAuth2Authorization.from(store.findById("authorization-8")).accessToken(access).build());
        assertThat(lastSeen("authorization-8")).isEqualTo(Timestamp.from(START.plus(Duration.ofMinutes(3))));
        clock.set(START.plus(Duration.ofMinutes(6)));
        service.save(OAuth2Authorization.from(store.findById("authorization-8")).invalidate(access).build());
        assertThat(lastSeen("authorization-8")).as("an invalidated token is not device activity").isEqualTo(Timestamp.from(START.plus(Duration.ofMinutes(3))));
    }

    @Test void registrationRollsBackWhenTheAuthorizationRecordCannotBeStored() {
        OAuth2AuthorizationService failing = new OAuth2AuthorizationService() {
            @Override public void save(OAuth2Authorization authorization) { throw new IllegalStateException("protocol store unavailable"); }
            @Override public void remove(OAuth2Authorization authorization) { }
            @Override public OAuth2Authorization findById(String id) { return null; }
            @Override public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) { return null; }
        };
        var service = new SessionAuthorizationService(failing, sessions, new InMemoryRegisteredClientRepository(client), manager);
        assertThatThrownBy(() -> service.save(pending("authorization-9").build())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_session WHERE session_id='authorization-9'", Integer.class)).isZero();
    }
}
