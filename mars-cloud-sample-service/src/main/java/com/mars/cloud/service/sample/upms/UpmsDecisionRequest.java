package com.mars.cloud.service.sample.upms;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * sample 自己维护的 UPMS 请求协议，不依赖 UPMS 服务源码。
 */
public record UpmsDecisionRequest(
        @JsonProperty("caller_id") String callerId,
        String action,
        String resource) {
}
