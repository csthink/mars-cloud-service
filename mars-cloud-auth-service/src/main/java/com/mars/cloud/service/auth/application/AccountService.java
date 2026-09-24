package com.mars.cloud.service.auth.application;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Account writes accept a phone only after the calling login flow has verified ownership. */
@Service
public final class AccountService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    public AccountService(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(manager); this.clock=clock;
    }
    public long registerVerifiedPhone(String phone) {
        validatePhone(phone);
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                List<Long> existing=identity(phone);
                if (!existing.isEmpty()) { requirePhone(existing.getFirst(),phone); return existing.getFirst(); }
                long id=IdWorker.getId(); Timestamp now=Timestamp.from(clock.instant());
                jdbc.update("INSERT INTO sys_user (user_id,phone,status,created_at,updated_at) VALUES (?,?,'ACTIVE',?,?)",id,phone,now,now);
                insertIdentity(id,phone,now); audit(id,"REGISTER"); return id;
            }));
        } catch (DuplicateKeyException ex) {
            List<Long> existing=identity(phone);
            if (existing.isEmpty()) throw ex;
            requirePhone(existing.getFirst(),phone); return existing.getFirst();
        }
    }
    public void rebindVerifiedPhone(long id,String oldPhone,String newPhone) {
        validatePhone(oldPhone); validatePhone(newPhone);
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_id=? AND status='ACTIVE' FOR UPDATE",Long.class,id);
            requirePhone(id,oldPhone);
            if (jdbc.update("DELETE FROM sys_user_identity WHERE user_id=? AND channel='SMS' AND identity_scope='e164' AND subject=?",id,oldPhone)!=1)
                throw new IllegalStateException("Phone identity is inconsistent");
            Timestamp now=Timestamp.from(clock.instant());
            insertIdentity(id,newPhone,now);
            jdbc.update("UPDATE sys_user SET phone=?,updated_at=? WHERE user_id=?",newPhone,now,id);
            audit(id,"PHONE_REBOUND");
        });
    }
    private List<Long> identity(String phone) {
        return jdbc.queryForList("SELECT user_id FROM sys_user_identity WHERE channel='SMS' AND identity_scope='e164' AND subject=?",Long.class,phone);
    }
    private void requirePhone(long id,String phone) {
        String actual=jdbc.queryForObject("SELECT phone FROM sys_user WHERE user_id=? AND status='ACTIVE'",String.class,id);
        if (!phone.equals(actual)) throw new IllegalStateException("Phone identity is inconsistent");
    }
    private void insertIdentity(long id,String phone,Timestamp now) {
        jdbc.update("INSERT INTO sys_user_identity (identity_id,user_id,channel,identity_scope,subject,verified_at,created_at,updated_at) VALUES (?,?,'SMS','e164',?,?,?,?)",IdWorker.getId(),id,phone,now,now,now);
    }
    public void audit(long id,String event) {
        jdbc.update("INSERT INTO sys_login_log (log_id,user_id,event,result,channel,created_at) VALUES (?,?,?,'SUCCESS','SMS',?)",IdWorker.getId(),id,event,Timestamp.from(clock.instant()));
    }
    private static void validatePhone(String phone) {
        if (phone==null || !phone.matches("\\+[1-9][0-9]{1,14}")) throw new IllegalArgumentException("A canonical E.164 phone is required");
    }
}
