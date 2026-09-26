package com.mars.cloud.service.auth.configuration;

import com.mars.cloud.service.auth.application.AccountService;
import com.mars.cloud.service.auth.application.AuthSessionService;
import com.mars.cloud.service.auth.application.DeviceSessionService;
import com.mars.cloud.service.auth.domain.RevokeReason;
import com.mars.cloud.service.auth.infrastructure.authorization.SessionAuthorizationService;
import com.mars.cloud.service.auth.infrastructure.client.SysClientRegisteredClientRepository;
import com.mars.cloud.service.auth.infrastructure.session.RedisRevocationStore;
import com.mars.cloud.service.auth.infrastructure.session.RevocationStore;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real MySQL and Redis probe. Rows are registered in the runtime order (database row first, backing store afterwards):
 * concurrent browser and native admissions keep exactly three devices without misjudging fresh rows, revocation removes
 * the authorization and the browser session and writes the marker, a failed marker write rolls the database back, stale
 * rows older than the admission grace are reconciled as EXPIRED, retention deletes old rows and the SMS risk counters
 * expire within a day.
 */
public final class DeviceSessionProbe {
    private static final MockServletContext CONTEXT = new MockServletContext();
    private static JdbcTemplate jdbc;

    public static void main(String[] args) throws Exception {
        var dataSource = new DriverManagerDataSource(required("SPRING_DATASOURCE_URL"), required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        check(database.equals(required("AUTH_VERIFY_DATABASE")) && database.contains("_verify_"), "Explicit verification database is required");
        var manager = new DataSourceTransactionManager(dataSource);
        var transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var factory = factory(required("SPRING_DATA_REDIS_HOST"), Integer.parseInt(required("SPRING_DATA_REDIS_PORT")));
        var unreachable = factory("127.0.0.1", 9);
        try {
            var template = new RedisTemplate<String, Object>();
            template.setConnectionFactory(factory);
            template.setKeySerializer(new StringRedisSerializer());
            template.setHashKeySerializer(new StringRedisSerializer());
            template.setDefaultSerializer(new AuthSessionConfiguration().springSessionDefaultRedisSerializer());
            template.afterPropertiesSet();
            var repository = new RedisIndexedSessionRepository(template);
            repository.setRedisKeyNamespace("mars:auth:session");
            var strings = new StringRedisTemplate(factory);
            var clients = new SysClientRegisteredClientRepository(jdbc, true);
            var store = new JdbcOAuth2AuthorizationService(jdbc, clients);
            var devices = new DeviceSessionService(jdbc, manager, Clock.systemUTC(), repository, () -> store, new RedisRevocationStore(strings));
            var sessions = new AuthSessionService(jdbc, Clock.systemUTC(), devices);
            long user = new AccountService(jdbc, manager, Clock.systemUTC()).registerVerifiedPhone(phone());
            RegisteredClient nativeClient = clients.findByClientId("test-native");
            check(nativeClient != null, "The verification database must register test-native");

            // Three browsers, then two concurrent fourth logins: exactly three stay active, the two oldest are evicted,
            // and the row committed by the first login is not misjudged by the second one waiting on the account lock.
            List<MockHttpServletRequest> browsers = new ArrayList<>();
            for (int i = 0; i < 3; i++) browsers.add(login(repository, sessions, transactions, user));
            List<MockHttpServletRequest> late = concurrently(() -> login(repository, sessions, transactions, user), () -> login(repository, sessions, transactions, user));
            check(active(user) == 3, "Concurrent browser admissions must leave exactly three active devices");
            check(count("SELECT COUNT(*) FROM sys_session WHERE user_id=? AND revoke_reason='DEVICE_LIMIT'", user) == 2, "Two devices are evicted for the limit");
            check(count("SELECT COUNT(*) FROM sys_session WHERE user_id=? AND revoke_reason='EXPIRED'", user) == 0, "Fresh rows are never misjudged as EXPIRED");
            for (int i = 0; i < 2; i++) {
                String evicted = sid(browsers.get(i));
                check(repository.findById(evicted) == null, "Evicted browser session is deleted from Redis");
                marker(strings, evicted);
            }
            check(repository.findById(sid(browsers.get(2))) != null && repository.findById(sid(late.get(0))) != null && repository.findById(sid(late.get(1))) != null, "Surviving sessions stay usable");
            check(count("SELECT COUNT(*) FROM sys_login_log WHERE user_id=? AND event='SESSION_REVOKED'", user) == 2, "Each eviction has one audit row");

            // Two concurrent native authorizations from one browser: the granting browser is never evicted and exactly
            // three devices stay active (the browser and the two native devices).
            MockHttpServletRequest holder = late.get(1);
            List<String> natives = concurrently(() -> nativeDevice(store, sessions, transactions, nativeClient, user, holder), () -> nativeDevice(store, sessions, transactions, nativeClient, user, holder));
            check(active(user) == 3, "Concurrent native admissions must leave exactly three active devices");
            check(revoked(sid(holder)) == null, "The browser granting the authorizations stays active");
            for (String id : natives) check(revoked(id) == null && count("SELECT COUNT(*) FROM sys_session WHERE session_id=? AND kind='NATIVE' AND client_id='test-native'", id) == 1, "Native rows use the authorization id and stay active");
            check(count("SELECT COUNT(*) FROM sys_session WHERE user_id=? AND revoke_reason='DEVICE_LIMIT'", user) == 4 && count("SELECT COUNT(*) FROM sys_session WHERE user_id=? AND revoke_reason='EXPIRED'", user) == 0, "Two more evictions, still no misjudged row");

            // Revocation side effects in order: authorization gone, browser session gone, marker present, audit written.
            String nativeId = natives.get(0);
            check(devices.revoke(nativeId, RevokeReason.ADMIN), "Native revocation must succeed");
            check(store.findById(nativeId) == null, "Revoked native authorization is removed");
            marker(strings, nativeId);
            check(revoked(nativeId).equals("ADMIN"), "Native row records the reason");
            check(!devices.revoke(nativeId, RevokeReason.ADMIN), "Second revocation is a no-op");
            String keeper = sid(holder);
            check(devices.revoke(keeper, RevokeReason.USER_DISABLED), "Browser revocation must succeed");
            check(repository.findById(keeper) == null, "Revoked browser session is deleted from Redis");
            marker(strings, keeper);
            check(count("SELECT COUNT(*) FROM sys_login_log WHERE user_id=? AND event='SESSION_REVOKED' AND details LIKE ?", user, "%" + nativeId + "%") == 1, "Revocation audit names the session");
            check(active(user) == 1, "One device remains after the two revocations");

            // A marker write that fails rolls the row and the authorization back.
            var broken = new DeviceSessionService(jdbc, manager, Clock.systemUTC(), repository, () -> store, new RedisRevocationStore(new StringRedisTemplate(unreachable)));
            MockHttpServletRequest holder2 = login(repository, sessions, transactions, user);
            String second = nativeDevice(store, sessions, transactions, nativeClient, user, holder2);
            try { broken.revoke(second, RevokeReason.ADMIN); throw new AssertionError("Revocation must fail when the marker cannot be written"); }
            catch (org.springframework.dao.DataAccessException | IllegalStateException expected) { }
            check(revoked(second) == null && store.findById(second) != null, "Failed marker write rolls back the row and keeps the authorization");
            check(!Boolean.TRUE.equals(strings.hasKey(RevocationStore.KEY_PREFIX + second)), "No marker after the failed write");

            // Rows older than the admission grace whose backing store disappeared are reconciled as EXPIRED.
            check(active(user) == 3, "Remaining native device, the second browser and its native device are active");
            repository.deleteById(sid(holder2));
            store.remove(store.findById(second));
            backdate(sid(holder2), second, natives.get(1));
            MockHttpServletRequest another = login(repository, sessions, transactions, user);
            check(revoked(sid(holder2)).equals("EXPIRED") && revoked(second).equals("EXPIRED"), "Stale rows are marked EXPIRED");
            check(revoked(natives.get(1)) == null, "A backed native row older than the grace stays active");
            check(!Boolean.TRUE.equals(strings.hasKey(RevocationStore.KEY_PREFIX + sid(holder2))), "EXPIRED rows get no marker");
            check(active(user) == 2 && revoked(sid(another)) == null, "Stale rows did not trigger an eviction");

            // Retention: rows revoked more than thirty days ago are purged, newer ones stay, and the purge uses an index.
            Instant now = Instant.now();
            String old = "probe-old-" + UUID.randomUUID();
            String recent = "probe-recent-" + UUID.randomUUID();
            insert(old, user, now.minus(Duration.ofDays(31)));
            insert(recent, user, now.minus(Duration.ofDays(29)));
            check(devices.purgeRevoked(now) >= 1, "Purge deletes at least the old row");
            check(count("SELECT COUNT(*) FROM sys_session WHERE session_id=?", old) == 0 && count("SELECT COUNT(*) FROM sys_session WHERE session_id=?", recent) == 1, "Only rows older than the retention are purged");
            check(count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='sys_session' AND index_name='idx_session_revoked'") == 1, "Retention index exists");

            // Risk counters written by the SMS acceptance expire within 24 hours (plus the one-second window guard).
            riskCounterLifetimes(strings);
            System.out.println("PASS: concurrent browser and native admissions, ordered revocation, marker rollback, expiry reconciliation, retention and risk counter lifetimes");
        } finally {
            unreachable.destroy();
            factory.destroy();
        }
    }

    private static LettuceConnectionFactory factory(String host, int port) {
        var config = new RedisStandaloneConfiguration(host, port);
        config.setDatabase(Integer.parseInt(required("SPRING_DATA_REDIS_DATABASE")));
        config.setPassword(RedisPassword.of(System.getenv("SPRING_DATA_REDIS_PASSWORD")));
        var factory = new LettuceConnectionFactory(config, LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3)).build());
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    /** Runs both actions at the same moment and returns their results in order. */
    private static <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> action : List.of(first, second)) futures.add(executor.submit(() -> { ready.countDown(); start.await(); return action.call(); }));
            check(ready.await(10, TimeUnit.SECONDS), "Both workers must be ready");
            start.countDown();
            return List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
        }
    }

    /** Registers the browser row inside a transaction and stores the Redis session afterwards, as the login response does. */
    private static MockHttpServletRequest login(RedisIndexedSessionRepository repository, AuthSessionService sessions, TransactionTemplate transactions, long user) {
        var stored = repository.createSession();
        var request = new MockHttpServletRequest(CONTEXT);
        request.setSession(new MockHttpSession(CONTEXT, stored.getId()));
        request.setRemoteAddr("198.51.100.30");
        request.addHeader("User-Agent", "device probe");
        transactions.executeWithoutResult(status -> sessions.login(Long.toString(user), request));
        repository.save(stored);
        return request;
    }

    /** Registers the native row first and stores the authorization record afterwards. */
    private static String nativeDevice(JdbcOAuth2AuthorizationService store, AuthSessionService sessions, TransactionTemplate transactions, RegisteredClient client, long user, MockHttpServletRequest browser) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        var owner = User.withUsername(Long.toString(user)).password("unused").authorities("LOCAL_LOGIN").build();
        var authorization = OAuth2Authorization.withRegisteredClient(client).id(id).principalName(Long.toString(user))
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizedScopes(Set.of("openid"))
                .attribute(SessionAuthorizationService.SID, id)
                .attribute(Principal.class.getName(), UsernamePasswordAuthenticationToken.authenticated(owner, null, owner.getAuthorities()))
                .accessToken(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "probe-access-" + id, now, now.plusSeconds(600), Set.of("openid")))
                .refreshToken(new OAuth2RefreshToken("probe-refresh-" + id, now, now.plusSeconds(3600))).build();
        transactions.executeWithoutResult(status -> sessions.authorizationSession(Long.toString(user), client.getClientId(), true, id, browser));
        store.save(authorization);
        return id;
    }

    private static void backdate(String... ids) {
        Timestamp past = Timestamp.from(Instant.now().minus(DeviceSessionService.ADMISSION_GRACE).minusSeconds(60));
        for (String id : ids) jdbc.update("UPDATE sys_session SET created_at=?,last_seen_at=? WHERE session_id=?", past, past, id);
    }

    private static void marker(StringRedisTemplate strings, String sid) {
        String key = RevocationStore.KEY_PREFIX + sid;
        check("1".equals(strings.opsForValue().get(key)), "Revocation marker must be 1");
        Long ttl = strings.getExpire(key, TimeUnit.MILLISECONDS);
        check(ttl != null && ttl > 890_000 && ttl <= 900_000, "Revocation marker must live fifteen minutes");
    }

    private static void riskCounterLifetimes(StringRedisTemplate strings) {
        var prefixes = List.of("mars:auth:sms:pm:", "mars:auth:sms:pd:", "mars:auth:sms:ih:", "mars:auth:sms:id:", "mars:auth:sms:wrong:", "mars:auth:captcha:sm:", "mars:auth:captcha:ih:");
        boolean sawWrong = false, sawDaily = false;
        try (var cursor = strings.scan(ScanOptions.scanOptions().match("mars:auth:*").count(500).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (prefixes.stream().noneMatch(key::startsWith)) continue;
                Long ttl = strings.getExpire(key, TimeUnit.MILLISECONDS);
                check(ttl != null && ttl > 0 && ttl <= 86_401_000L, "Risk counter must expire within 24 hours");
                sawWrong |= key.startsWith("mars:auth:sms:wrong:");
                sawDaily |= key.startsWith("mars:auth:sms:pd:");
            }
        }
        check(sawWrong && sawDaily, "The SMS acceptance must have left wrong-code and daily counters to inspect");
    }

    private static void insert(String id, long user, Instant revokedAt) {
        jdbc.update("INSERT INTO sys_session (session_id,user_id,kind,created_at,last_seen_at,revoked_at,revoke_reason) VALUES (?,?,'BROWSER',?,?,?,'DEVICE_LIMIT')",
                id, user, Timestamp.from(revokedAt.minusSeconds(60)), Timestamp.from(revokedAt.minusSeconds(60)), Timestamp.from(revokedAt));
    }

    private static String sid(MockHttpServletRequest request) { return request.getSession(false).getId(); }
    private static int active(long user) { return count("SELECT COUNT(*) FROM sys_session WHERE user_id=? AND revoked_at IS NULL", user); }
    private static String revoked(String sid) { return jdbc.queryForObject("SELECT revoke_reason FROM sys_session WHERE session_id=?", String.class, sid); }
    private static int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static String phone() { return "+999" + String.format("%011d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000_000L)); }
    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name); return value; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
