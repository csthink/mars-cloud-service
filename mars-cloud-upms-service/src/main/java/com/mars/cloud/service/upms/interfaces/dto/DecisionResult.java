package com.mars.cloud.service.upms.interfaces.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DecisionResult(
        String decision,
        @JsonProperty("reason_code") String reasonCode,
        @JsonProperty("decision_id") String decisionId
) {
}
