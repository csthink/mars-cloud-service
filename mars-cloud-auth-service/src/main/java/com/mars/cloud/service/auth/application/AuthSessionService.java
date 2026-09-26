package com.mars.cloud.service.auth.application;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthSessionService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final DeviceSessionService devices;
    public AuthSessionService(JdbcTemplate jdbc,Clock clock,DeviceSessionService devices) { this.jdbc=jdbc; this.clock=clock; this.devices=devices; }
    @Transactional
    public void login(String userId,HttpServletRequest request) {
        String sid=request.getSession(false).getId();
        devices.admit(Long.parseLong(userId),DeviceSessionService.BROWSER,null,request);
        insert(sid,userId,null,DeviceSessionService.BROWSER,request);
        jdbc.update("INSERT INTO sys_login_log (log_id,user_id,event,result,ip,user_agent,created_at) VALUES (?,?,'LOGIN','SUCCESS',?,?,?)",
                IdWorker.getId(),Long.parseLong(userId),request.getRemoteAddr(),bounded(request.getHeader("User-Agent"),512),Timestamp.from(clock.instant()));
    }
    /**
     * Associates an authorization with a device session. Browser clients reuse the authenticated browser session and
     * refresh its last activity; native clients get their own device row whose identifier is the protocol authorization id.
     */
    @Transactional
    public String authorizationSession(String userId,String clientId,boolean nativeClient,String authorizationId,HttpServletRequest request) {
        var session=request.getSession(false);
        if (session==null) throw new IllegalStateException("Authenticated browser session is required");
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM sys_session WHERE session_id=? AND user_id=? AND revoked_at IS NULL",Integer.class,session.getId(),Long.parseLong(userId));
        if (count==null || count!=1) throw new IllegalStateException("Authenticated session is not registered");
        if (!nativeClient) {
            jdbc.update("UPDATE sys_session SET last_seen_at=? WHERE session_id=?",Timestamp.from(clock.instant()),session.getId());
            return session.getId();
        }
        if (authorizationId==null || !authorizationId.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalStateException("Authorization identifier is not a usable session identifier");
        devices.admit(Long.parseLong(userId),DeviceSessionService.NATIVE,clientId,request);
        insert(authorizationId,userId,clientId,DeviceSessionService.NATIVE,request);
        return authorizationId;
    }
    /** Records activity of a native device when its authorization issues or refreshes tokens. */
    @Transactional
    public void touchNative(String sessionId) {
        jdbc.update("UPDATE sys_session SET last_seen_at=? WHERE session_id=? AND kind='NATIVE' AND revoked_at IS NULL",Timestamp.from(clock.instant()),sessionId);
    }
    private void insert(String sid,String userId,String clientId,String kind,HttpServletRequest request) {
        Timestamp now=Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO sys_session (session_id,user_id,client_id,kind,device_label,ip,created_at,last_seen_at) VALUES (?,?,?,?,?,?,?,?)",
                sid,Long.parseLong(userId),clientId,kind,bounded(request.getHeader("User-Agent"),200),request.getRemoteAddr(),now,now);
    }
    private static String bounded(String value,int max) { return value==null?null:value.substring(0,Math.min(value.length(),max)); }
}
