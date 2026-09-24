package com.mars.cloud.service.auth.infrastructure.sms;


public final class UnavailableSmsSender implements SmsSender {
    @Override public boolean available() { return false; }
    @Override public void send(String phone,String challengeId,String code) { throw SmsFailure.unavailable(); }
}
