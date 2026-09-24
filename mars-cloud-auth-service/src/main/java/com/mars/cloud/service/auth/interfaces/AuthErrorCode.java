package com.mars.cloud.service.auth.interfaces;

import com.mars.cloud.common.error.ErrorCode;

public enum AuthErrorCode implements ErrorCode {
    ACCOUNT_UNAVAILABLE(64001);
    private final int code;
    AuthErrorCode(int code) { this.code=code; }
    @Override public int getCode() { return code; }
}
