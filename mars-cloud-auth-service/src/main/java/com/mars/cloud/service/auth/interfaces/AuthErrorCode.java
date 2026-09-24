package com.mars.cloud.service.auth.interfaces;

import com.mars.cloud.common.error.ErrorCode;

public enum AuthErrorCode implements ErrorCode {
    ACCOUNT_UNAVAILABLE(64001),
    SMS_CHALLENGE_INVALID(64002), CAPTCHA_REQUIRED(64003), CAPTCHA_INVALID(64004),
    SMS_SEND_LIMITED(64005), SMS_UNAVAILABLE(64006);
    private final int code;
    AuthErrorCode(int code) { this.code=code; }
    @Override public int getCode() { return code; }
}
