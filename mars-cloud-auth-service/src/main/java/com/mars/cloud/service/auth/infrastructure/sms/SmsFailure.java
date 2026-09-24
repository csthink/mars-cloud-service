package com.mars.cloud.service.auth.infrastructure.sms;

import com.mars.cloud.mvc.exception.HttpException;
import com.mars.cloud.service.auth.interfaces.AuthErrorCode;

public final class SmsFailure extends HttpException {
    private SmsFailure(int status, AuthErrorCode code) { super(status,code); }
    public static SmsFailure invalid() { return new SmsFailure(400,AuthErrorCode.SMS_CHALLENGE_INVALID); }
    public static SmsFailure captchaRequired() { return new SmsFailure(428,AuthErrorCode.CAPTCHA_REQUIRED); }
    public static SmsFailure captchaInvalid() { return new SmsFailure(400,AuthErrorCode.CAPTCHA_INVALID); }
    public static SmsFailure limited() { return new SmsFailure(429,AuthErrorCode.SMS_SEND_LIMITED); }
    public static SmsFailure unavailable() { return new SmsFailure(503,AuthErrorCode.SMS_UNAVAILABLE); }
}
