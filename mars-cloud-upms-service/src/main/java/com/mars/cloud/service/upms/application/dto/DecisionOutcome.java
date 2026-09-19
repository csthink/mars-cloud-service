package com.mars.cloud.service.upms.application.dto;

public record DecisionOutcome(String decision, String reasonCode, String decisionId) {

    public static DecisionOutcome from(com.mars.cloud.service.upms.domain.decision.DecisionOutcome outcome,
                                       String decisionId) {
        return new DecisionOutcome(outcome.decision(), outcome.reasonCode(), decisionId);
    }
}
