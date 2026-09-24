package com.mars.cloud.service.auth.verification;

import com.mars.cloud.service.auth.configuration.AuthProperties;
import com.mars.cloud.service.auth.infrastructure.sms.LocalCaptchaProvider;
import com.mars.cloud.service.auth.infrastructure.sms.SmsChallengeStore;
import com.mars.cloud.service.auth.infrastructure.sms.SmsCrypto;
import com.mars.cloud.service.auth.infrastructure.sms.SmsRedis;
import com.mars.cloud.service.auth.infrastructure.sms.SmsRiskService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Exercises the SMS Lua scripts against the caller's isolated Redis database. */
public final class SmsRiskProbe {
    public static void main(String[] args) throws Exception {
        var config=new RedisStandaloneConfiguration(required("SPRING_DATA_REDIS_HOST"),
                Integer.parseInt(required("SPRING_DATA_REDIS_PORT")));
        config.setDatabase(Integer.parseInt(required("SPRING_DATA_REDIS_DATABASE")));
        String password=System.getenv("SPRING_DATA_REDIS_PASSWORD");
        if (password!=null && !password.isEmpty()) config.setPassword(RedisPassword.of(password));
        var factory=new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        try {
            var template=new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            var redis=new SmsRedis(template);
            var properties=new AuthProperties();
            properties.getSms().setHmacKey(required("MARS_AUTH_SMS_HMAC_KEY"));
            var crypto=new SmsCrypto(properties);
            var store=new SmsChallengeStore(redis,crypto);
            var date=LocalDate.now(ZoneOffset.UTC).plusDays(1);
            var clock=new MutableClock(date.atStartOfDay(ZoneOffset.UTC).toInstant());
            var risk=new SmsRiskService(redis,crypto,properties,clock);
            String suffix=UUID.randomUUID().toString().replace("-","");
            long seed=Math.floorMod(UUID.randomUUID().getMostSignificantBits(),1_000_000_000L);
            String phone="+1999"+String.format("%010d",seed);
            String session="session-"+suffix;
            String id=SmsCrypto.randomId();
            store.put(phone,"LOGIN",session,id,"111111");
            String replacement=SmsCrypto.randomId();
            store.put(phone,"LOGIN",session,replacement,"222222");
            check(store.verify(phone,"LOGIN",session,id,"111111")==0,"New code must replace old code");
            check(store.verify(phone,"REBIND_NEW",session,replacement,"222222")==0,"Purpose must be isolated");
            check(store.verify(phone,"LOGIN",session+"other",replacement,"222222")==0,"Session must be bound");
            for (int i=0;i<5;i++) check(store.verify(phone,"LOGIN",session,replacement,"000000")==2,"Wrong code consumes one attempt");
            check(store.verify(phone,"LOGIN",session,replacement,"222222")==0,"Sixth attempt must fail");
            id=SmsCrypto.randomId();
            store.put(phone,"LOGIN",session,id,"333333");
            String concurrentId=id;
            var start=new CountDownLatch(1);
            try (var workers=Executors.newFixedThreadPool(2)) {
                var first=workers.submit(() -> {start.await();return store.verify(phone,"LOGIN",session,concurrentId,"333333");});
                var second=workers.submit(() -> {start.await();return store.verify(phone,"LOGIN",session,concurrentId,"333333");});
                start.countDown();
                check(List.of(first.get(),second.get()).stream().mapToInt(Integer::intValue).sum()==1,
                        "Concurrent correct submissions must have one winner");
            }
            id=SmsCrypto.randomId();
            store.put(phone,"LOGIN",session,id,"444444");
            String challengeKey="mars:auth:sms:challenge:"+crypto.digest("phone",phone)+":LOGIN";
            template.expire(challengeKey,Duration.ofMillis(1));
            Thread.sleep(25);
            check(store.verify(phone,"LOGIN",session,id,"444444")==0,"Expired code must fail");
            var captcha=new LocalCaptchaProvider(redis,crypto,risk);
            String captchaId=SmsCrypto.randomId(), answer="ABCDE";
            String captchaKey="mars:auth:captcha:challenge:"+captchaId;
            template.opsForHash().put(captchaKey,"purpose","SEND");
            template.opsForHash().put(captchaKey,"session",crypto.digest("captcha-session",session));
            template.opsForHash().put(captchaKey,"hash",crypto.digest("captcha-answer",captchaId+":"+answer));
            template.expire(captchaKey,Duration.ofMinutes(2));
            check(!captcha.consume("VERIFY",session,captchaId,answer),"Wrong captcha purpose must fail");
            check(!captcha.consume("SEND",session,captchaId,answer),"Captcha must be consumed on mismatch");
            captchaId=SmsCrypto.randomId();
            captchaKey="mars:auth:captcha:challenge:"+captchaId;
            template.opsForHash().put(captchaKey,"purpose","SEND");
            template.opsForHash().put(captchaKey,"session",crypto.digest("captcha-session",session));
            template.opsForHash().put(captchaKey,"hash",crypto.digest("captcha-answer",captchaId+":"+answer));
            template.expire(captchaKey,Duration.ofMinutes(2));
            check(captcha.consume("SEND",session,captchaId,answer),"Correct captcha must pass");
            check(!captcha.consume("SEND",session,captchaId,answer),"Captcha replay must fail");
            for (int i=0;i<10;i++) captcha.create("SEND",session,"probe-captcha-"+suffix);
            check("10".equals(template.opsForValue().get("mars:auth:captcha:sm:"+
                    crypto.digest("captcha-session",session))),"Captcha generation counter must reach ten");
            String ip="probe-minute-"+suffix;
            check(risk.reserve(phone,ip,false)==SmsRiskService.Reservation.OK,"First send must reserve");
            check(risk.reserve(phone,ip,false)==SmsRiskService.Reservation.LIMITED,"Same phone minute limit must apply");
            String ipLimit="probe-hour-"+suffix;
            for (int i=0;i<10;i++) {
                String candidate="+1888"+String.format("%010d",seed+i);
                if (i==3) check(risk.reserve(candidate,ipLimit,false)==SmsRiskService.Reservation.CAPTCHA_REQUIRED,
                        "Fourth IP send must require captcha");
                check(risk.reserve(candidate,ipLimit,true)==SmsRiskService.Reservation.OK,"IP must allow ten sends");
            }
            check(risk.reserve("+1777"+String.format("%010d",seed),ipLimit,true)==SmsRiskService.Reservation.LIMITED,
                    "Eleventh IP send must fail");
            String dailyPhone="+1666"+String.format("%010d",seed);
            for (int i=0;i<10;i++) {
                clock.advance(Duration.ofSeconds(61));
                if (i==3) check(risk.reserve(dailyPhone,"probe-phone-"+suffix+"-"+i,false)==SmsRiskService.Reservation.CAPTCHA_REQUIRED,
                        "Fourth phone send must require captcha");
                check(risk.reserve(dailyPhone,"probe-phone-"+suffix+"-"+i,true)==SmsRiskService.Reservation.OK,
                        "Phone must allow ten sends in a day");
            }
            clock.advance(Duration.ofSeconds(61));
            check(risk.reserve(dailyPhone,"probe-phone-"+suffix+"-20",true)==SmsRiskService.Reservation.LIMITED,
                    "Eleventh phone send must fail");
            String dailyIp="probe-day-"+suffix;
            for (int hour=0;hour<5;hour++) {
                clock.advance(Duration.ofHours(1).plusSeconds(1));
                for (int i=0;i<10;i++) {
                    String candidate="+1555"+String.format("%010d",seed+100+hour*10+i);
                    check(risk.reserve(candidate,dailyIp,true)==SmsRiskService.Reservation.OK,
                            "IP must allow fifty sends in a day");
                }
            }
            check(risk.reserve("+1444"+String.format("%010d",seed),dailyIp,true)==SmsRiskService.Reservation.LIMITED,
                    "Fifty-first IP send must fail");
            for (int i=0;i<3;i++) risk.wrongCode(phone,session);
            check(risk.verificationCaptchaRequired(phone,session),"Third wrong code must require captcha");
            String paused="mars:auth:sms:budget:paused", alert="mars:auth:sms:budget:alert";
            check(!Boolean.TRUE.equals(template.hasKey(paused)),"Probe requires an unpaused isolated Redis database");
            String exhaustionDate=date.plusDays(1).toString();
            String budgetDate=date.plusDays(2).toString(), nextDate=date.plusDays(3).toString();
            try {
                properties.getSms().setDailyBudget(1);
                clock.set(date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
                check(risk.reserve(phone+"final","probe-final-"+suffix,true)==SmsRiskService.Reservation.OK,
                        "Final budget slot must still be sent");
                check(Boolean.TRUE.equals(template.hasKey(paused)) && Boolean.TRUE.equals(template.hasKey(alert)),
                        "Final allowed reservation must pause and alert immediately");
                template.delete(List.of(paused,alert,"mars:auth:sms:budget:"+exhaustionDate));
                properties.getSms().setDailyBudget(2);
                clock.set(date.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant());
                var budgetStart=new CountDownLatch(1);
                try (var workers=Executors.newFixedThreadPool(3)) {
                    var results=new java.util.ArrayList<java.util.concurrent.Future<SmsRiskService.Reservation>>();
                    for (int i=0;i<3;i++) {
                        String candidate=phone+i, address="probe-budget-"+suffix+"-"+i;
                        results.add(workers.submit(() -> {budgetStart.await();return risk.reserve(candidate,address,true);}));
                    }
                    budgetStart.countDown();
                    int allowed=0,denied=0;
                    for (var result:results) {
                        if (result.get()==SmsRiskService.Reservation.OK) allowed++; else denied++;
                    }
                    check(allowed==2 && denied==1,"Concurrent budget must allow only two sends");
                }
                clock.set(date.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant());
                check(risk.reserve(phone+"3","probe-budget-"+suffix+"-3",true)==SmsRiskService.Reservation.PAUSED,"Pause survives UTC rollover");
            } finally {
                template.delete(List.of(paused,alert,"mars:auth:sms:budget:"+exhaustionDate,
                        "mars:auth:sms:budget:"+budgetDate,
                        "mars:auth:sms:budget:"+nextDate,"mars:auth:sms:budget:"+date));
            }
            System.out.println("PASS: SMS code replacement, binding, expiry, five attempts, atomic consumption, captcha use, phone and IP limits, budget pause");
        } finally { factory.destroy(); }
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing "+name);
        return value;
    }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant=instant; }
        private void advance(Duration duration) { instant=instant.plus(duration); }
        private void set(Instant value) { instant=value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
