package com.mars.cloud.service.sample.upms;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * sample 自己维护的 UPMS 成功结果协议。
 */
public record UpmsDecisionResult(
        String decision,
        @JsonProperty("reason_code") String reasonCode,
        @JsonProperty("decision_id") String decisionId) {
}
