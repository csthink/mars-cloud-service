package com.mars.cloud.service.auth.infrastructure.sms;

import com.mars.cloud.service.auth.configuration.AuthProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class SmsRiskService {
    private static final Logger LOG = LoggerFactory.getLogger(SmsRiskService.class);
    private static final String RESERVE = """
        if redis.call('EXISTS', KEYS[6]) == 1 then return 4 end
        local now = tonumber(ARGV[1])
        local windows = {60000, 86400000, 3600000, 86400000}
        local limits = {1, 10, 10, 50}
        local counts = {}
        for i=1,4 do
          redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now-windows[i])
          counts[i] = redis.call('ZCARD', KEYS[i])
        end
        if (counts[3] >= 3 or counts[2] >= 3) and ARGV[4] ~= '1' then return 2 end
        for i=1,4 do if counts[i] >= limits[i] then return 1 end end
        local budget = tonumber(redis.call('GET', KEYS[5]) or '0')
        if budget >= tonumber(ARGV[3]) then
          redis.call('SET', KEYS[6], '1')
          return 4
        end
        for i=1,4 do
          redis.call('ZADD', KEYS[i], now, ARGV[2])
          redis.call('PEXPIRE', KEYS[i], windows[i]+1000)
        end
        budget = redis.call('INCR', KEYS[5])
        redis.call('EXPIREAT', KEYS[5], tonumber(ARGV[5]))
        if budget >= tonumber(ARGV[3]) then
          redis.call('SET', KEYS[6], '1')
          return 3
        end
        return 0
        """;
    private static final String CAPTCHA_RATE = """
        local a=tonumber(redis.call('GET', KEYS[1]) or '0')
        local b=tonumber(redis.call('GET', KEYS[2]) or '0')
        if a>=10 or b>=30 then return 1 end
        if redis.call('INCR', KEYS[1]) == 1 then redis.call('PEXPIRE', KEYS[1], 60000) end
        if redis.call('INCR', KEYS[2]) == 1 then redis.call('PEXPIRE', KEYS[2], 3600000) end
        return 0
        """;
    private static final String FAIL = """
        local count=redis.call('INCR', KEYS[1])
        if count==1 then redis.call('PEXPIRE', KEYS[1], 86400000) end
        return count
        """;
    private final SmsRedis redis;
    private final SmsCrypto crypto;
    private final AuthProperties properties;
    private final Clock clock;
    public SmsRiskService(SmsRedis redis, SmsCrypto crypto, AuthProperties properties, Clock clock) {
        this.redis=redis; this.crypto=crypto; this.properties=properties; this.clock=clock;
    }
    public enum Reservation { OK, LIMITED, CAPTCHA_REQUIRED, PAUSED }
    public Reservation reserve(String phone, String ip, boolean captchaPassed) {
        long now=clock.millis();
        LocalDate today=clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        long midnight=today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String p=crypto.digest("phone",phone), a=crypto.digest("ip",ip);
        List<String> keys=List.of("mars:auth:sms:pm:"+p,"mars:auth:sms:pd:"+p,
                "mars:auth:sms:ih:"+a,"mars:auth:sms:id:"+a,
                "mars:auth:sms:budget:"+today,"mars:auth:sms:budget:paused");
        long result=redis.run(RESERVE,keys,Long.toString(now),SmsCrypto.randomId(),
                Integer.toString(properties.getSms().getDailyBudget()),captchaPassed?"1":"0",Long.toString(midnight+86400));
        if (result==3 || result==4) {
            Boolean first=redis.template().opsForValue().setIfAbsent("mars:auth:sms:budget:alert",today.toString());
            if (Boolean.TRUE.equals(first)) LOG.warn("SMS daily budget paused");
        }
        return switch ((int)result) {
            case 0 -> Reservation.OK;
            case 3 -> Reservation.OK;
            case 1 -> Reservation.LIMITED;
            case 2 -> Reservation.CAPTCHA_REQUIRED;
            case 4 -> Reservation.PAUSED;
            default -> throw new IllegalStateException("Unknown SMS reservation result");
        };
    }
    public void reserveCaptcha(String session, String ip) {
        String s=crypto.digest("captcha-session",session), a=crypto.digest("captcha-ip",ip);
        if (redis.run(CAPTCHA_RATE,List.of("mars:auth:captcha:sm:"+s,"mars:auth:captcha:ih:"+a))!=0)
            throw SmsFailure.limited();
    }
    public boolean verificationCaptchaRequired(String phone,String session) {
        String key=wrongKey(phone,session);
        String count=redis.template().opsForValue().get(key);
        return count!=null && Integer.parseInt(count)>=properties.getSms().getCaptchaErrorThreshold();
    }
    public void wrongCode(String phone,String session) { redis.run(FAIL,List.of(wrongKey(phone,session))); }
    private String wrongKey(String phone,String session) {
        return "mars:auth:sms:wrong:"+crypto.digest("wrong",phone+":"+session);
    }
}
