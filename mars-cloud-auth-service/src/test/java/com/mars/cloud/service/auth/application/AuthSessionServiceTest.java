package com.mars.cloud.service.auth.application;

import static com.mars.cloud.service.auth.application.DeviceTestSupport.START;
import static com.mars.cloud.service.auth.application.DeviceTestSupport.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;

class AuthSessionServiceTest {
    private JdbcTemplate jdbc;
    private MapSessionRepository sessions;
    private AuthSessionService service;
    private final List<String> markers = new ArrayList<>();
    private final DeviceTestSupport.TestClock clock = new DeviceTestSupport.TestClock();
    private final MockServletContext context = new MockServletContext();

    @BeforeEach void setUp() {
        var source = DeviceTestSupport.dataSource();
        jdbc = new JdbcTemplate(source);
        sessions = new MapSessionRepository(new ConcurrentHashMap<>());
        var store = new JdbcOAuth2AuthorizationService(jdbc, new InMemoryRegisteredClientRepository(DeviceTestSupport.nativeClient()));
        var devices = new DeviceSessionService(jdbc, new DataSourceTransactionManager(source), clock, sessions, () -> store, markers::add);
        service = new AuthSessionService(jdbc, clock, devices);
    }

    /** A request whose HTTP session is also stored in the session repository, as Spring Session does at runtime. */
    private MockHttpServletRequest browserRequest() {
        MapSession stored = sessions.createSession();
        sessions.save(stored);
        var request = new MockHttpServletRequest(context);
        request.setSession(new MockHttpSession(context, stored.getId()));
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("User-Agent", "browser " + stored.getId().substring(0, 4));
        return request;
    }

    private Map<String, Object> rowOf(String id) { return jdbc.queryForMap("SELECT kind,client_id,last_seen_at,revoked_at,revoke_reason FROM sys_session WHERE session_id=?", id); }

    @Test void loginRegistersTheBrowserSessionAndWritesTheLoginAudit() {
        var request = browserRequest();
        service.login(Long.toString(USER), request);
        assertThat(rowOf(request.getSession(false).getId())).containsEntry("KIND", "BROWSER").containsEntry("LAST_SEEN_AT", Timestamp.from(START));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_login_log WHERE user_id=? AND event='LOGIN'", Integer.class, USER)).isEqualTo(1);
        assertThat(markers).isEmpty();
    }

    @Test void fourthBrowserLoginEvictsTheLeastRecentlySeenBrowser() {
        var first = browserRequest();
        service.login(Long.toString(USER), first);
        clock.set(START.plus(Duration.ofMinutes(1)));
        service.login(Long.toString(USER), browserRequest());
        clock.set(START.plus(Duration.ofMinutes(2)));
        service.login(Long.toString(USER), browserRequest());
        clock.set(START.plus(Duration.ofMinutes(3)));
        var fourth = browserRequest();
        service.login(Long.toString(USER), fourth);
        String evicted = first.getSession(false).getId();
        assertThat(rowOf(evicted)).containsEntry("REVOKE_REASON", "DEVICE_LIMIT");
        assertThat(sessions.findById(evicted)).isNull();
        assertThat(sessions.findById(fourth.getSession(false).getId())).isNotNull();
        assertThat(markers).containsExactly(evicted);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_session WHERE user_id=? AND revoked_at IS NULL", Integer.class, USER)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_login_log WHERE event='SESSION_REVOKED'", Integer.class)).isEqualTo(1);
    }

    @Test void nativeAuthorizationUsesTheAuthorizationIdAsItsDeviceSessionAndCountsAsADevice() {
        var request = browserRequest();
        service.login(Long.toString(USER), request);
        clock.set(START.plus(Duration.ofMinutes(1)));
        assertThat(service.authorizationSession(Long.toString(USER), "test-native", true, "authorization-1", request)).isEqualTo("authorization-1");
        assertThat(rowOf("authorization-1")).containsEntry("KIND", "NATIVE").containsEntry("CLIENT_ID", "test-native");
        clock.set(START.plus(Duration.ofMinutes(2)));
        assertThat(service.authorizationSession(Long.toString(USER), "portal", false, "authorization-2", request)).isEqualTo(request.getSession(false).getId());
        assertThat(rowOf(request.getSession(false).getId())).containsEntry("LAST_SEEN_AT", Timestamp.from(START.plus(Duration.ofMinutes(2))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_session WHERE session_id='authorization-2'", Integer.class)).isZero();
        assertThatThrownBy(() -> service.authorizationSession(Long.toString(USER), "test-native", true, "bad sid", request)).isInstanceOf(IllegalStateException.class);
    }

    @Test void touchNativeUpdatesOnlyActiveNativeRows() {
        var request = browserRequest();
        service.login(Long.toString(USER), request);
        service.authorizationSession(Long.toString(USER), "test-native", true, "authorization-1", request);
        DeviceTestSupport.row(jdbc, "revoked-native", "NATIVE", "test-native", START, START, "ADMIN");
        clock.set(START.plus(Duration.ofMinutes(5)));
        service.touchNative("authorization-1");
        service.touchNative("revoked-native");
        service.touchNative(request.getSession(false).getId());
        assertThat(rowOf("authorization-1")).containsEntry("LAST_SEEN_AT", Timestamp.from(START.plus(Duration.ofMinutes(5))));
        assertThat(rowOf("revoked-native")).containsEntry("LAST_SEEN_AT", Timestamp.from(START));
        assertThat(rowOf(request.getSession(false).getId())).containsEntry("LAST_SEEN_AT", Timestamp.from(START));
    }
}
