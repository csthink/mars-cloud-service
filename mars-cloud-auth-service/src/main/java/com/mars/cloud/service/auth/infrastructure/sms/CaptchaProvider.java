package com.mars.cloud.service.auth.infrastructure.sms;

public interface CaptchaProvider {
    record Image(String id,byte[] png) { }
    Image create(String purpose,String session,String ip);
    boolean consume(String purpose,String session,String id,String answer);
}
