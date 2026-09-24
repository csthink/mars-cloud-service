package com.mars.cloud.service.auth.infrastructure.sms;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class SmsChallengeStore {
    private static final String PUT = """
        redis.call('HSET', KEYS[1], 'id', ARGV[1], 'hash', ARGV[2], 'session', ARGV[3], 'purpose', ARGV[4], 'failures', 0)
        redis.call('PEXPIRE', KEYS[1], 300000)
        return 1
        """;
    private static final String VERIFY = """
        if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
        if redis.call('HGET', KEYS[1], 'id') ~= ARGV[1] or
           redis.call('HGET', KEYS[1], 'session') ~= ARGV[2] or
           redis.call('HGET', KEYS[1], 'purpose') ~= ARGV[3] then return 0 end
        if redis.call('HGET', KEYS[1], 'hash') == ARGV[4] then
          redis.call('DEL', KEYS[1])
          return 1
        end
        local failures=redis.call('HINCRBY', KEYS[1], 'failures', 1)
        if failures >= 5 then redis.call('DEL', KEYS[1]) end
        return 2
        """;
    private final SmsRedis redis;
    private final SmsCrypto crypto;
    public SmsChallengeStore(SmsRedis redis,SmsCrypto crypto) { this.redis=redis; this.crypto=crypto; }
    public void put(String phone,String purpose,String session,String id,String code) {
        redis.run(PUT,List.of(key(phone,purpose)),id,crypto.digest("code",id+":"+code),
                crypto.digest("session",session),purpose);
    }
    public int verify(String phone,String purpose,String session,String id,String code) {
        return (int)redis.run(VERIFY,List.of(key(phone,purpose)),id,crypto.digest("session",session),
                purpose,crypto.digest("code",id+":"+code));
    }
    private String key(String phone,String purpose) {
        return "mars:auth:sms:challenge:"+crypto.digest("phone",phone)+":"+purpose;
    }
}
