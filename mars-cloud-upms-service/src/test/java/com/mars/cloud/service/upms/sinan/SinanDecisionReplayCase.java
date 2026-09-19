package com.mars.cloud.service.upms.sinan;

public record SinanDecisionReplayCase(
        String callerId,
        String action,
        String resource,
        String expectedDecision,
        String sourceLabel
) {
}
