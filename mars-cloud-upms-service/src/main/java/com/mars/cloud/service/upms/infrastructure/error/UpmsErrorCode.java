package com.mars.cloud.service.upms.infrastructure.error;

import com.mars.cloud.common.error.ErrorCode;

public enum UpmsErrorCode implements ErrorCode {
    UIMS_PROTOCOL_ERROR(65001),
    UIMS_SNAPSHOT_UNAVAILABLE(65002),
    UIMS_INTERNAL_ERROR(65003);

    private final int code;

    UpmsErrorCode(int code) {
        this.code = code;
    }

    @Override
    public int getCode() {
        return code;
    }
}
