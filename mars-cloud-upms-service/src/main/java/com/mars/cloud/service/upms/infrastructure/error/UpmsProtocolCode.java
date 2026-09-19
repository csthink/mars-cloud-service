package com.mars.cloud.service.upms.infrastructure.error;

public enum UpmsProtocolCode {
    MISSING_FIELD("missing_field"),
    EMPTY_FIELD("empty_field"),
    MALFORMED_REQUEST("malformed_request"),
    MALFORMED_RESOURCE("malformed_resource"),
    SNAPSHOT_UNAVAILABLE("snapshot_unavailable"),
    INTERNAL_ERROR("internal_error");

    private final String code;

    UpmsProtocolCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
