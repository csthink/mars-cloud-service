package com.mars.cloud.service.auth.infrastructure.sms;

public interface SmsSender {
    void send(String phone,String challengeId,String code);
    boolean available();
}
