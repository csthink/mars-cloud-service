package com.mars.cloud.service.upms.interfaces.dto;

import com.mars.cloud.service.upms.infrastructure.error.UimsProtocolCode;

public class ProtocolRequestException extends RuntimeException {

    private final UimsProtocolCode code;

    public ProtocolRequestException(UimsProtocolCode code, String message) {
        super(message);
        this.code = code;
    }

    public UimsProtocolCode code() {
        return code;
    }
}
