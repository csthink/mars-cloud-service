package com.mars.cloud.service.upms.domain.decision;

public record DecisionOutcome(String decision, String reasonCode) {

    public static DecisionOutcome allow() {
        return new DecisionOutcome("allow", "granted");
    }

    public static DecisionOutcome deny(String reasonCode) {
        return new DecisionOutcome("deny", reasonCode);
    }
}
