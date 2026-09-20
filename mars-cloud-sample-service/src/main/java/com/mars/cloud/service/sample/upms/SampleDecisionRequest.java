package com.mars.cloud.service.sample.upms;

import jakarta.validation.constraints.NotBlank;

/**
 * sample 演示端点接收的授权请求。调用方身份由服务端构造，不接受外部输入。
 */
public record SampleDecisionRequest(
        @NotBlank(message = "action 不能为空") String action,
        @NotBlank(message = "resource 不能为空") String resource) {
}
