package com.mars.cloud.service.auth.verification;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.mars.cloud.service.auth.application.AccountService;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit real-MySQL probe. Writes only unique fixtures to a caller-approved verification database. */
public final class DatabaseProbe {
    private static JdbcTemplate jdbc;
    private static String scope;
    public static void main(String[] args) throws Exception {
        var dataSource=new DriverManagerDataSource(required("SPRING_DATASOURCE_URL"),required("SPRING_DATASOURCE_USERNAME"),required("SPRING_DATASOURCE_PASSWORD"));
        jdbc=new JdbcTemplate(dataSource);
        String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
        check(database.equals(required("AUTH_VERIFY_DATABASE")) && database.contains("_verify_"),"Explicit verification database is required");
        check(jdbc.queryForObject("SELECT @@innodb_page_size",Integer.class)==16384,"Index checks require a 16 KiB page");
        var account=new AccountService(jdbc,new DataSourceTransactionManager(dataSource),Clock.systemUTC());
        String phone="+999"+String.format("%011d",Math.floorMod(UUID.randomUUID().getMostSignificantBits(),100_000_000_000L));
        var start=new CountDownLatch(1);
        List<Long> users;
        try (var executor=Executors.newFixedThreadPool(6)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for(int i=0;i<6;i++) futures.add(executor.submit(() -> {start.await();return account.registerVerifiedPhone(phone);}));
            start.countDown();users=new java.util.ArrayList<>();
            for(var future:futures)users.add(future.get());
        }
        check(new HashSet<>(users).size()==1,"Concurrent registration must create one account");
        long user=users.getFirst();
        check(count("SELECT COUNT(*) FROM sys_user WHERE phone=?",phone)==1,"One account owns the phone");
        check(count("SELECT COUNT(*) FROM sys_user_identity WHERE channel='SMS' AND identity_scope='e164' AND subject=?",phone)==1,"One verified identity owns the phone");
        check(count("SELECT COUNT(*) FROM sys_login_log WHERE user_id=? AND event='REGISTER'",user)==1,"Registration audit commits once");
        String occupied=phone.substring(0,phone.length()-1)+(phone.endsWith("9")?"0":"9");
        long other=account.registerVerifiedPhone(occupied);
        expectRejected(() -> {account.rebindVerifiedPhone(user,phone,occupied);return null;});
        check(phone.equals(jdbc.queryForObject("SELECT phone FROM sys_user WHERE user_id=?",String.class,user)),"Failed rebind must preserve old phone");
        check(count("SELECT COUNT(*) FROM sys_login_log WHERE user_id=? AND event='PHONE_REBOUND'",user)==0,"Failed rebind must roll back audit");
        check(count("SELECT COUNT(*) FROM sys_user_identity WHERE user_id=? AND subject=?",user,phone)==1,"Failed rebind must preserve identity");
        String replacement=phone.substring(0,phone.length()-2)+"88";
        if(replacement.equals(phone)||replacement.equals(occupied))replacement=phone.substring(0,phone.length()-2)+"77";
        account.rebindVerifiedPhone(user,phone,replacement);
        check(count("SELECT COUNT(*) FROM sys_user_identity WHERE user_id=? AND channel='SMS'",user)==1,"Rebind retains one current SMS identity");
        check(count("SELECT COUNT(*) FROM sys_user_identity WHERE subject=? AND channel='SMS'",phone)==0,"Old SMS identity is removed");
        check(count("SELECT COUNT(*) FROM sys_login_log WHERE user_id=? AND event='PHONE_REBOUND'",user)==1,"Successful rebind includes audit");
        concurrentRebind(account);
        String definition=jdbc.queryForMap("SHOW CREATE TABLE sys_user_identity").get("Create Table").toString();
        scope="probe-"+UUID.randomUUID();
        identity(user,"EXAMPLE",scope,"subject",null);
        identity(user,"SECOND",scope,"subject",null);
        identity(user,"EXAMPLE",scope+"-other","subject",null);
        for(String subject:List.of("Subject","súbject","subject ","x".repeat(254)+"a","x".repeat(254)+"b"))identity(user,"EXAMPLE",scope,subject,null);
        expectRejected(() -> {identity(user,"EXAMPLE",scope,"subject",null);return null;});
        expectRejected(() -> {identity(other,"EXAMPLE",scope,"subject",null);return null;});
        for(String subject:java.util.Arrays.asList(null,"","x".repeat(256)))expectRejected(() -> {identity(user,"EXAMPLE",scope,subject,null);return null;});
        expectRejected(() -> {identity(user,"lowercase",scope,"subject",null);return null;});
        expectRejected(() -> {identity(user,"EXAMPLE","","subject",null);return null;});
        expectRejected(() -> {identity(user,"EXAMPLE",scope,"array","[]");return null;});
        expectRejected(() -> {identity(user,"EXAMPLE",scope,"large","{\"value\":\""+"x".repeat(8192)+"\"}");return null;});
        check(count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='sys_user_identity' AND index_name='uk_identity_subject' AND sub_part IS NOT NULL")==0,"Identity unique index uses complete values");
        jdbc.update("INSERT INTO sys_login_log(log_id,user_id,event,result,channel,created_at) VALUES(?,?,'PROBE','SUCCESS','EXAMPLE',UTC_TIMESTAMP(6))",IdWorker.getId(),user);
        check(definition.equals(jdbc.queryForMap("SHOW CREATE TABLE sys_user_identity").get("Create Table")),"New channel must not change table structure");
        check(count("SELECT COUNT(*) FROM sys_user_credential WHERE user_id IN (?,?)",user,other)==0,"SMS must not store a password credential");
        System.out.println("PASS: MySQL concurrent registration and competing rebind, rollback, rebind audit, channel extension, full identity index, collation and input constraints");
    }
    private static void concurrentRebind(AccountService account) throws Exception {
        String prefix="+998"+String.format("%010d",Math.floorMod(UUID.randomUUID().getMostSignificantBits(),10_000_000_000L));
        String first=prefix+"1",second=prefix+"2",target=prefix+"3";
        long firstUser=account.registerVerifiedPhone(first),secondUser=account.registerVerifiedPhone(second);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        List<Boolean> outcomes;
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> competeForPhone(account,firstUser,first,target,ready,start));
            var b=executor.submit(() -> competeForPhone(account,secondUser,second,target,ready,start));
            check(ready.await(10,java.util.concurrent.TimeUnit.SECONDS),"Both rebind workers must be ready");
            start.countDown();
            outcomes=List.of(a.get(30,java.util.concurrent.TimeUnit.SECONDS),b.get(30,java.util.concurrent.TimeUnit.SECONDS));
        }
        check(outcomes.stream().filter(Boolean::booleanValue).count()==1,"Exactly one competing rebind succeeds");
        long winner=outcomes.getFirst()?firstUser:secondUser,loser=outcomes.getFirst()?secondUser:firstUser;
        String winnerOld=outcomes.getFirst()?first:second,loserOld=outcomes.getFirst()?second:first;
        check(target.equals(jdbc.queryForObject("SELECT phone FROM sys_user WHERE user_id=?",String.class,winner)),"Winning account owns new phone");
        check(loserOld.equals(jdbc.queryForObject("SELECT phone FROM sys_user WHERE user_id=?",String.class,loser)),"Losing account retains old phone");
        check(count("SELECT COUNT(*) FROM sys_user_identity WHERE channel='SMS' AND identity_scope='e164' AND user_id=? AND subject=?",winner,target)==1,"Winning SMS identity commits");
        check(count("SELECT COUNT(*) FROM sys_user_identity WHERE channel='SMS' AND identity_scope='e164' AND user_id=? AND subject=?",loser,loserOld)==1,"Losing SMS identity rolls back");
        check(count("SELECT COUNT(*) FROM sys_user_identity WHERE channel='SMS' AND identity_scope='e164' AND subject=?",winnerOld)==0,"Winning old identity is removed");
        check(count("SELECT COUNT(*) FROM sys_user_identity WHERE channel='SMS' AND user_id IN (?,?)",winner,loser)==2,"Concurrent rebind leaves one identity per account");
        check(count("SELECT COUNT(*) FROM sys_login_log WHERE user_id=? AND event='PHONE_REBOUND'",winner)==1,"Winning rebind audit commits once");
        check(count("SELECT COUNT(*) FROM sys_login_log WHERE user_id=? AND event='PHONE_REBOUND'",loser)==0,"Losing rebind audit rolls back");
        check(account.registerVerifiedPhone(target)==winner,"Subsequent verified login resolves the winning account");
    }
    private static boolean competeForPhone(AccountService account,long user,String oldPhone,String newPhone,CountDownLatch ready,CountDownLatch start) throws Exception {
        ready.countDown();start.await();
        try {account.rebindVerifiedPhone(user,oldPhone,newPhone);return true;}
        catch(DataIntegrityViolationException conflict) {return false;}
    }
    private static void identity(long user,String channel,String identityScope,String subject,String attributes) {
        jdbc.update("INSERT INTO sys_user_identity(identity_id,user_id,channel,identity_scope,subject,attributes,verified_at,created_at,updated_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",IdWorker.getId(),user,channel,identityScope,subject,attributes);
    }
    private static int count(String sql,Object... args) {return jdbc.queryForObject(sql,Integer.class,args);}
    private static String required(String name) {String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+name);return value;}
    private static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void expectRejected(Callable<?> action) throws Exception {
        try {action.call();} catch(DataIntegrityViolationException expected) {return;}
        catch(org.springframework.jdbc.UncategorizedSQLException expected) {
            if(expected.getSQLException().getErrorCode()==3819)return;
            throw expected;
        }
        throw new AssertionError("Database accepted an invalid or conflicting identity");
    }
}
