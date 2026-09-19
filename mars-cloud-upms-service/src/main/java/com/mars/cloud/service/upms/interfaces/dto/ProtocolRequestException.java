package com.mars.cloud.service.upms.interfaces.dto;

import com.mars.cloud.service.upms.infrastructure.error.UpmsProtocolCode;

public class ProtocolRequestException extends RuntimeException {

    private final UpmsProtocolCode code;

    public ProtocolRequestException(UpmsProtocolCode code, String message) {
        super(message);
        this.code = code;
    }

    public UpmsProtocolCode code() {
        return code;
    }
}
