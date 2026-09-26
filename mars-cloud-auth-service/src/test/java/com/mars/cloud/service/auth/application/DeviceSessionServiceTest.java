package com.mars.cloud.service.auth.application;

import static com.mars.cloud.service.auth.application.DeviceTestSupport.START;
import static com.mars.cloud.service.auth.application.DeviceTestSupport.USER;
import static com.mars.cloud.service.auth.application.DeviceTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mars.cloud.service.auth.domain.RevokeReason;
import com.mars.cloud.service.auth.infrastructure.session.RevocationStore;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;

class DeviceSessionServiceTest {
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private MapSessionRepository sessions;
    private RegisteredClient client;
    private JdbcOAuth2AuthorizationService store;
    private final List<String> markers = new ArrayList<>();
    private final DeviceTestSupport.TestClock clock = new DeviceTestSupport.TestClock();

    @BeforeEach void setUp() {
        source = DeviceTestSupport.dataSource();
        jdbc = new JdbcTemplate(source);
        sessions = new MapSessionRepository(new ConcurrentHashMap<>());
        client = DeviceTestSupport.nativeClient();
        store = new JdbcOAuth2AuthorizationService(jdbc, new InMemoryRegisteredClientRepository(client));
    }

    private DeviceSessionService service(RevocationStore revocations) {
        return new DeviceSessionService(jdbc, new DataSourceTransactionManager(source), clock, sessions, () -> store, revocations);
    }

    private DeviceSessionService service() { return service(markers::add); }

    private String browser(Instant lastSeen) {
        MapSession session = sessions.createSession();
        sessions.save(session);
        row(jdbc, session.getId(), "BROWSER", null, lastSeen, null, null);
        return session.getId();
    }

    private String nativeDevice(Instant lastSeen, boolean withAuthorization) {
        String id = UUID.randomUUID().toString();
        if (withAuthorization) store.save(DeviceTestSupport.authorization(client, id, "refresh-" + id, Set.of("openid")));
        row(jdbc, id, "NATIVE", "test-native", lastSeen, null, null);
        return id;
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("User-Agent", "fourth device");
        return request;
    }

    private Map<String, Object> rowOf(String id) { return jdbc.queryForMap("SELECT revoked_at,revoke_reason FROM sys_session WHERE session_id=?", id); }
    private int audits() { return jdbc.queryForObject("SELECT COUNT(*) FROM sys_login_log WHERE event='SESSION_REVOKED'", Integer.class); }

    @Test void fourthDeviceEvictsTheLeastRecentlySeenDevice() {
        String oldest = browser(START.minus(Duration.ofHours(3)));
        String nativeId = nativeDevice(START.minus(Duration.ofHours(2)), true);
        String recent = browser(START.minus(Duration.ofHours(1)));
        service().admit(USER, "NATIVE", "csthink-assistant", request());
        assertThat(rowOf(oldest)).containsEntry("REVOKE_REASON", "DEVICE_LIMIT").containsEntry("REVOKED_AT", Timestamp.from(START));
        assertThat(sessions.findById(oldest)).isNull();
        assertThat(rowOf(recent).get("REVOKED_AT")).isNull();
        assertThat(sessions.findById(recent)).isNotNull();
        assertThat(rowOf(nativeId).get("REVOKED_AT")).isNull();
        assertThat(store.findById(nativeId)).isNotNull();
        assertThat(markers).containsExactly(oldest);
        var audit = jdbc.queryForMap("SELECT user_id,client_id,ip,user_agent,details FROM sys_login_log WHERE event='SESSION_REVOKED'");
        assertThat(audit).containsEntry("USER_ID", USER).containsEntry("CLIENT_ID", "csthink-assistant").containsEntry("IP", "203.0.113.9").containsEntry("USER_AGENT", "fourth device");
        assertThat(audit.get("DETAILS").toString()).contains("\"reason\":\"DEVICE_LIMIT\"").contains("\"session_id\":\"" + oldest + "\"")
                .contains("\"kind\":\"BROWSER\"").contains("\"trigger_kind\":\"NATIVE\"").contains("\"trigger_client_id\":\"csthink-assistant\"");
    }

    @Test void moreThanThreeStaleActiveRowsAreEvictedDownToTwo() {
        for (int i = 0; i < 5; i++) browser(START.minus(Duration.ofMinutes(10 - i)));
        service().admit(USER, "BROWSER", null, request());
        assertThat(service().activeDevices(USER)).hasSize(2);
        assertThat(markers).hasSize(3);
        assertThat(audits()).isEqualTo(3);
    }

    @Test void devicesWhoseBackingStoreIsGoneAreMarkedExpiredAndNotCounted() {
        String staleBrowser = UUID.randomUUID().toString();
        row(jdbc, staleBrowser, "BROWSER", null, START.minus(Duration.ofDays(31)), null, null);
        String staleNative = nativeDevice(START.minus(Duration.ofDays(2)), false);
        String alive = browser(START.minus(Duration.ofHours(1)));
        service().admit(USER, "BROWSER", null, request());
        assertThat(rowOf(staleBrowser)).containsEntry("REVOKE_REASON", "EXPIRED");
        assertThat(rowOf(staleNative)).containsEntry("REVOKE_REASON", "EXPIRED");
        assertThat(rowOf(alive).get("REVOKED_AT")).isNull();
        assertThat(markers).isEmpty();
        assertThat(audits()).isZero();
    }

    @Test void rowsRegisteredWithinTheGraceWindowAreAliveBeforeTheirBackingStoreExists() {
        String justLoggedIn = UUID.randomUUID().toString();
        row(jdbc, justLoggedIn, "BROWSER", null, START.minusSeconds(30), null, null);
        String justAuthorized = UUID.randomUUID().toString();
        row(jdbc, justAuthorized, "NATIVE", "test-native", START.minusSeconds(5), null, null);
        String older = browser(START.minus(Duration.ofHours(1)));
        service().admit(USER, "BROWSER", null, request());
        assertThat(rowOf(justLoggedIn).get("REVOKED_AT")).isNull();
        assertThat(rowOf(justAuthorized).get("REVOKED_AT")).isNull();
        assertThat(rowOf(older)).as("the two rows in the grace window count, so the older backed browser is evicted").containsEntry("REVOKE_REASON", "DEVICE_LIMIT");
        assertThat(markers).containsExactly(older);
    }

    @Test void nativeAuthorizationWithoutLiveTokensCountsAsExpired() {
        String id = UUID.randomUUID().toString();
        Instant past = Instant.now().minusSeconds(3600);
        var expiredAccess = new org.springframework.security.oauth2.core.OAuth2AccessToken(org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER, "old-" + id, past.minusSeconds(900), past, Set.of("openid"));
        store.save(org.springframework.security.oauth2.server.authorization.OAuth2Authorization.withRegisteredClient(client).id(id).principalName(Long.toString(USER))
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE).authorizedScopes(Set.of("openid"))
                .attribute(com.mars.cloud.service.auth.infrastructure.authorization.SessionAuthorizationService.SID, id).accessToken(expiredAccess).build());
        row(jdbc, id, "NATIVE", "test-native", START.minus(Duration.ofHours(2)), null, null);
        browser(START.minus(Duration.ofHours(1)));
        browser(START.minus(Duration.ofMinutes(30)));
        service().admit(USER, "BROWSER", null, request());
        assertThat(rowOf(id)).containsEntry("REVOKE_REASON", "EXPIRED");
        assertThat(store.findById(id)).isNull();
        assertThat(markers).isEmpty();
        assertThat(service().activeDevices(USER)).hasSize(2);
    }

    @Test void revokingRemovesTheAuthorizationAndTheSessionBeforeTheMarkerIsWritten() {
        String nativeId = nativeDevice(START, true);
        String browserId = browser(START);
        Consumer<String> ordering = id -> {
            assertThat(store.findById(id)).isNull();
            assertThat(sessions.findById(id)).isNull();
            assertThat(rowOf(id).get("REVOKED_AT")).isNotNull();
            markers.add(id);
        };
        var service = service(ordering::accept);
        assertThat(service.revoke(nativeId, RevokeReason.ADMIN)).isTrue();
        assertThat(service.revoke(browserId, RevokeReason.USER_DISABLED)).isTrue();
        assertThat(markers).containsExactly(nativeId, browserId);
        assertThat(rowOf(nativeId)).containsEntry("REVOKE_REASON", "ADMIN");
        assertThat(rowOf(browserId)).containsEntry("REVOKE_REASON", "USER_DISABLED");
        assertThat(audits()).isEqualTo(2);
        var audit = jdbc.queryForMap("SELECT client_id,ip,details FROM sys_login_log WHERE event='SESSION_REVOKED' AND details LIKE ?", "%" + nativeId + "%");
        assertThat(audit.get("CLIENT_ID")).isNull();
        assertThat(audit.get("IP")).isNull();
        assertThat(audit.get("DETAILS").toString()).contains("\"reason\":\"ADMIN\"").contains("\"client_id\":\"test-native\"").doesNotContain("trigger_kind");
    }

    @Test void failedMarkerWriteRollsBackTheDatabaseChanges() {
        String nativeId = nativeDevice(START, true);
        String browserId = browser(START);
        var service = service(id -> { throw new IllegalStateException("revocation store unavailable"); });
        assertThatThrownBy(() -> service.revoke(nativeId, RevokeReason.ADMIN)).isInstanceOf(IllegalStateException.class);
        assertThat(rowOf(nativeId).get("REVOKED_AT")).isNull();
        assertThat(store.findById(nativeId)).isNotNull();
        assertThatThrownBy(() -> service.revoke(browserId, RevokeReason.ADMIN)).isInstanceOf(IllegalStateException.class);
        assertThat(rowOf(browserId).get("REVOKED_AT")).isNull();
        assertThat(sessions.findById(browserId)).as("the browser session is deleted before the marker; the row stays active and is reconciled as EXPIRED later").isNull();
        assertThat(audits()).isZero();
        assertThat(service().activeDevices(USER)).hasSize(2);
    }

    @Test void revokeIsIdempotentAndUnknownIdentifiersAreIgnored() {
        String id = browser(START);
        var service = service();
        assertThat(service.revoke(id, RevokeReason.ADMIN)).isTrue();
        assertThat(service.revoke(id, RevokeReason.ADMIN)).isFalse();
        assertThat(service.revoke("unknown-session", RevokeReason.ADMIN)).isFalse();
        assertThat(markers).containsExactly(id);
        assertThat(audits()).isEqualTo(1);
    }

    @Test void revokeAllRevokesEveryActiveDeviceOfTheAccount() {
        browser(START); browser(START.plusSeconds(1)); nativeDevice(START.plusSeconds(2), true);
        String otherUser = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sys_user (user_id) VALUES (10002)");
        jdbc.update("INSERT INTO sys_session (session_id,user_id,kind,created_at,last_seen_at) VALUES (?,?,?,?,?)", otherUser, 10002L, "BROWSER", Timestamp.from(START), Timestamp.from(START));
        assertThat(service().revokeAll(USER, RevokeReason.USER_DISABLED)).isEqualTo(3);
        assertThat(service().activeDevices(USER)).isEmpty();
        assertThat(markers).hasSize(3);
        assertThat(audits()).isEqualTo(3);
        assertThat(rowOf(otherUser).get("REVOKED_AT")).isNull();
    }

    @Test void purgeDeletesOnlyRowsRevokedLongerAgoThanTheRetention() {
        Instant now = START;
        row(jdbc, "old", "BROWSER", null, now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(30)).minusSeconds(1), "DEVICE_LIMIT");
        row(jdbc, "boundary", "BROWSER", null, now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(30)), "DEVICE_LIMIT");
        row(jdbc, "recent", "NATIVE", "test-native", now.minus(Duration.ofDays(20)), now.minus(Duration.ofDays(29)), "EXPIRED");
        row(jdbc, "active", "BROWSER", null, now.minus(Duration.ofDays(45)), null, null);
        assertThat(service().purgeRevoked(now)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT session_id FROM sys_session ORDER BY session_id", String.class)).containsExactly("active", "boundary", "recent");
        assertThat(service().purgeRevoked(now)).isZero();
    }
}
