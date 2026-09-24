package com.mars.cloud.service.auth.infrastructure.sms;

import org.springframework.security.core.AuthenticationException;

public final class SmsAuthenticationException extends AuthenticationException {
    private final int status;
    private final int code;
    public SmsAuthenticationException(int status,int code) {
        super("SMS authentication failed");
        this.status=status; this.code=code;
    }
    public int status() { return status; }
    public int code() { return code; }
}
