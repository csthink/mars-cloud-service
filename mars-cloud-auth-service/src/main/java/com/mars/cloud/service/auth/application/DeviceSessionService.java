package com.mars.cloud.service.auth.application;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.mars.cloud.service.auth.domain.RevokeReason;
import com.mars.cloud.service.auth.infrastructure.session.RevocationStore;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Device sessions of one account: the concurrent device limit, revocation with its side effects and retention.
 * Every write runs in the caller's database transaction when one exists, otherwise in its own. Admissions of one
 * account are serialized by a lock on its sys_user row; the transaction must take that lock before reading device rows,
 * and it must read committed data (not a REPEATABLE READ snapshot taken before the lock), which is why the callers run
 * at READ COMMITTED.
 */
public class DeviceSessionService {
    public static final int MAX_DEVICES = 3;
    public static final Duration RETENTION = Duration.ofDays(30);
    public static final String AUDIT_EVENT = "SESSION_REVOKED";
    /** A row registered this recently is alive even before its browser session or authorization record is stored. */
    public static final Duration ADMISSION_GRACE = Duration.ofSeconds(60);
    static final String BROWSER = "BROWSER";
    static final String NATIVE = "NATIVE";
    private static final int PURGE_BATCH = 1000;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ACTIVE_ROWS =
            "SELECT session_id,user_id,kind,client_id,last_seen_at,created_at FROM sys_session WHERE user_id=? AND revoked_at IS NULL ORDER BY last_seen_at,created_at,session_id";
    private static final String ROW_BY_ID =
            "SELECT session_id,user_id,kind,client_id,last_seen_at,created_at FROM sys_session WHERE session_id=? AND revoked_at IS NULL";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SessionRepository<? extends Session> sessions;
    private final Supplier<OAuth2AuthorizationService> authorizations;
    private final RevocationStore revocations;

    public DeviceSessionService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                                SessionRepository<? extends Session> sessions, Supplier<OAuth2AuthorizationService> authorizations,
                                RevocationStore revocations) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.clock = clock;
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.sessions = sessions; this.authorizations = authorizations; this.revocations = revocations;
    }

    /** One active device row of an account. */
    public record Device(String sessionId, long userId, String kind, String clientId, Instant lastSeenAt, Instant createdAt) { }

    /** The login or authorization that caused an eviction; written into the audit row. */
    public record Trigger(String kind, String clientId, String ip, String userAgent) {
        public static Trigger of(String kind, String clientId, HttpServletRequest request) {
            return new Trigger(kind, clientId, request.getRemoteAddr(), bounded(request.getHeader("User-Agent"), 512));
        }
    }

    /** Active devices of an account, least recently seen first; stale rows are reported as they are stored. */
    public List<Device> activeDevices(long userId) {
        return jdbc.query(ACTIVE_ROWS, DeviceSessionService::device, userId);
    }

    /**
     * Serializes the admissions of one account for the rest of the current transaction. Callers that read device rows
     * before admitting must take this lock first, otherwise a REPEATABLE READ snapshot could predate a concurrent admission.
     */
    public void lockAccount(long userId) {
        jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE", Long.class, userId);
    }

    /**
     * Makes room for one more device: serializes admissions of the account, marks rows whose backing store is gone as
     * EXPIRED, then evicts the least recently seen devices until fewer than {@link #MAX_DEVICES} remain.
     */
    public void admit(long userId, String kind, String clientId, HttpServletRequest request) {
        Trigger trigger = Trigger.of(kind, clientId, request);
        transactions.executeWithoutResult(status -> {
            lockAccount(userId);
            var active = new ArrayList<Device>();
            for (Device device : activeDevices(userId)) {
                if (alive(device)) active.add(device); else revoke(device, RevokeReason.EXPIRED, null);
            }
            while (active.size() >= MAX_DEVICES) revoke(active.removeFirst(), RevokeReason.DEVICE_LIMIT, trigger);
        });
    }

    /** Revokes one device; returns false when it was already revoked or never existed. */
    public boolean revoke(String sessionId, RevokeReason reason) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var rows = jdbc.query(ROW_BY_ID, DeviceSessionService::device, sessionId);
            if (rows.isEmpty()) return false;
            return revoke(rows.getFirst(), reason, null);
        }));
    }

    /** Revokes every active device of an account, for example when the account is disabled; returns how many. */
    public int revokeAll(long userId, RevokeReason reason) {
        Integer count = transactions.execute(status -> {
            int revoked = 0;
            for (Device device : activeDevices(userId)) if (revoke(device, reason, null)) revoked++;
            return revoked;
        });
        return count == null ? 0 : count;
    }

    /** Deletes rows revoked more than {@link #RETENTION} before {@code now}; idempotent and batched. */
    public int purgeRevoked(Instant now) {
        Timestamp cutoff = Timestamp.from(now.minus(RETENTION));
        int total = 0;
        while (true) {
            List<String> ids = jdbc.queryForList("SELECT session_id FROM sys_session WHERE revoked_at IS NOT NULL AND revoked_at<? ORDER BY revoked_at,session_id LIMIT " + PURGE_BATCH, String.class, cutoff);
            if (ids.isEmpty()) return total;
            Integer deleted = transactions.execute(status -> {
                int count = 0;
                for (String id : ids) count += jdbc.update("DELETE FROM sys_session WHERE session_id=? AND revoked_at IS NOT NULL AND revoked_at<?", id, cutoff);
                return count;
            });
            total += deleted == null ? 0 : deleted;
            if (ids.size() < PURGE_BATCH) return total;
        }
    }

    private static Device device(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Device(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant());
    }

    /**
     * Whether the device can still be used. The browser session is stored by Spring Session when the login response
     * commits and the authorization record is stored after its row, so rows inside {@link #ADMISSION_GRACE} are alive by
     * construction; older rows are checked against their backing store.
     */
    private boolean alive(Device device) {
        if (device.createdAt().isAfter(clock.instant().minus(ADMISSION_GRACE))) return true;
        if (BROWSER.equals(device.kind())) return sessions.findById(device.sessionId()) != null;
        OAuth2Authorization authorization = authorizations.get().findById(device.sessionId());
        return authorization != null && (active(authorization.getRefreshToken()) || active(authorization.getAccessToken())
                || active(authorization.getToken(OAuth2AuthorizationCode.class)));
    }

    private static boolean active(OAuth2Authorization.Token<?> token) { return token != null && token.isActive(); }

    /**
     * Ordered side effects inside the current transaction: the protocol authorization row goes first because the code
     * exchange locks that row before touching sys_session, then the sys_session row, the browser session, the revocation
     * marker (not for EXPIRED rows, which have no live token) and finally the audit row.
     */
    private boolean revoke(Device device, RevokeReason reason, Trigger trigger) {
        Timestamp now = Timestamp.from(clock.instant());
        if (NATIVE.equals(device.kind())) {
            OAuth2AuthorizationService service = authorizations.get();
            OAuth2Authorization authorization = service.findById(device.sessionId());
            if (authorization != null) service.remove(authorization);
        }
        int updated = jdbc.update("UPDATE sys_session SET revoked_at=?,revoke_reason=? WHERE session_id=? AND revoked_at IS NULL", now, reason.name(), device.sessionId());
        if (updated == 0) return false;
        if (BROWSER.equals(device.kind())) sessions.deleteById(device.sessionId());
        if (reason == RevokeReason.EXPIRED) return true;
        revocations.revoke(device.sessionId());
        var details = new LinkedHashMap<String, Object>();
        details.put("reason", reason.name());
        details.put("session_id", device.sessionId());
        details.put("kind", device.kind());
        if (device.clientId() != null) details.put("client_id", device.clientId());
        details.put("last_seen_at", device.lastSeenAt().toString());
        if (trigger != null) {
            details.put("trigger_kind", trigger.kind());
            if (trigger.clientId() != null) details.put("trigger_client_id", trigger.clientId());
        }
        jdbc.update("INSERT INTO sys_login_log (log_id,user_id,event,result,client_id,ip,user_agent,created_at,details) VALUES (?,?,?,'SUCCESS',?,?,?,?,?)",
                IdWorker.getId(), device.userId(), AUDIT_EVENT, trigger == null ? null : trigger.clientId(),
                trigger == null ? null : trigger.ip(), trigger == null ? null : trigger.userAgent(), now, JSON.writeValueAsString(details));
        return true;
    }

    private static String bounded(String value, int max) { return value == null ? null : value.substring(0, Math.min(value.length(), max)); }
}
