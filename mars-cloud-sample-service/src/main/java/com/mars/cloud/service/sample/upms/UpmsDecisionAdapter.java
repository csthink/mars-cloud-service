package com.mars.cloud.service.sample.upms;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.common.response.UnifyResponse;
import com.mars.cloud.mvc.exception.HttpException;
import com.mars.cloud.service.sample.error.SampleErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 对领域侧隐藏 Feign 与传输信封，并为演示调用建立服务端可信身份上下文。
 */
@Service
public final class UpmsDecisionAdapter {

    private static final CallerContext DEMO_CALLER = new CallerContext(
            "caller-allow", "mars-cloud-sample-service", "default");

    private final UpmsDecisionClient client;

    public UpmsDecisionAdapter(UpmsDecisionClient client) {
        this.client = client;
    }

    public UpmsDecisionResult decide(SampleDecisionRequest request) {
        try (CallerContextHolder.Scope ignored = CallerContextHolder.open(DEMO_CALLER)) {
            UnifyResponse<UpmsDecisionResult> response = client.decide(new UpmsDecisionRequest(
                    DEMO_CALLER.subject(), request.action(), request.resource()));
            if (response == null || response.getResult() == null
                    || !("allow".equals(response.getResult().decision()) || "deny".equals(response.getResult().decision()))
                    || response.getResult().reasonCode() == null || response.getResult().reasonCode().isBlank()
                    || response.getResult().decisionId() == null || response.getResult().decisionId().isBlank()) {
                throw new HttpException(
                        HttpStatus.BAD_GATEWAY.value(), SampleErrorCode.UPMS_RESPONSE_INVALID);
            }
            return response.getResult();
        }
    }
}
