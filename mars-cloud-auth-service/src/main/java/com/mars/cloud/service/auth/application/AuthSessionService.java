package com.mars.cloud.service.auth.application;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthSessionService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public AuthSessionService(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    @Transactional
    public void login(String userId,HttpServletRequest request) {
        String sid=request.getSession(false).getId();
        insert(sid,userId,null,"BROWSER",request);
        jdbc.update("INSERT INTO sys_login_log (log_id,user_id,event,result,ip,user_agent,created_at) VALUES (?,?,'LOGIN','SUCCESS',?,?,?)",
                IdWorker.getId(),Long.parseLong(userId),request.getRemoteAddr(),bounded(request.getHeader("User-Agent"),512),Timestamp.from(clock.instant()));
    }
    @Transactional
    public String authorizationSession(String userId,String clientId,boolean nativeClient,HttpServletRequest request) {
        var session=request.getSession(false);
        if (session==null) throw new IllegalStateException("Authenticated browser session is required");
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM sys_session WHERE session_id=? AND user_id=? AND revoked_at IS NULL",Integer.class,session.getId(),Long.parseLong(userId));
        if (count==null || count!=1) throw new IllegalStateException("Authenticated session is not registered");
        if (!nativeClient) {
            jdbc.update("UPDATE sys_session SET last_seen_at=? WHERE session_id=?",Timestamp.from(clock.instant()),session.getId());
            return session.getId();
        }
        String id=UUID.randomUUID().toString(); insert(id,userId,clientId,"NATIVE",request); return id;
    }
    private void insert(String sid,String userId,String clientId,String kind,HttpServletRequest request) {
        Timestamp now=Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO sys_session (session_id,user_id,client_id,kind,device_label,ip,created_at,last_seen_at) VALUES (?,?,?,?,?,?,?,?)",
                sid,Long.parseLong(userId),clientId,kind,bounded(request.getHeader("User-Agent"),200),request.getRemoteAddr(),now,now);
    }
    private static String bounded(String value,int max) { return value==null?null:value.substring(0,Math.min(value.length(),max)); }
}
