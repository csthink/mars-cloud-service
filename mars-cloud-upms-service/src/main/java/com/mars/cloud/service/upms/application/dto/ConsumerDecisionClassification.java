package com.mars.cloud.service.upms.application.dto;

public record ConsumerDecisionClassification(Type type, String decision, String code) {

    public enum Type {
        DECISION,
        INTEGRATION_BUG,
        SERVICE_FAILURE,
        PROTOCOL_ERROR
    }

    public static ConsumerDecisionClassification decision(String decision) {
        return new ConsumerDecisionClassification(Type.DECISION, decision, null);
    }

    public static ConsumerDecisionClassification integrationBug(String code) {
        return new ConsumerDecisionClassification(Type.INTEGRATION_BUG, null, code);
    }

    public static ConsumerDecisionClassification serviceFailure(String code) {
        return new ConsumerDecisionClassification(Type.SERVICE_FAILURE, null, code);
    }

    public static ConsumerDecisionClassification protocolError() {
        return new ConsumerDecisionClassification(Type.PROTOCOL_ERROR, null, "protocol_error");
    }
}
