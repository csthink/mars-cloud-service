package com.mars.cloud.service.sample.upms;

import com.mars.cloud.mvc.exception.HttpException;
import com.mars.cloud.security.*;
import com.mars.cloud.service.sample.error.SampleErrorCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Adapts authenticated permission decisions to the sample application's established response contract. */
@Service
public final class UpmsDecisionAdapter {
    private final PdpClient client;
    public UpmsDecisionAdapter(PdpClient client) { this.client = client; }
    public UpmsDecisionResult decide(SampleDecisionRequest request) {
        var caller = AuthenticatedCaller.from(SecurityContextHolder.getContext().getAuthentication());
        try {
            var decision = client.decide(caller, request.action(), request.resource());
            return new UpmsDecisionResult(decision.allowed() ? "allow" : "deny", decision.reasonCode(), decision.decisionId());
        } catch (SecurityFailure failure) {
            if (failure.code() == SecurityErrorCode.PDP_UNAVAILABLE)
                throw new HttpException(503, SampleErrorCode.UPMS_UNAVAILABLE);
            if (failure.code() == SecurityErrorCode.PDP_PROTOCOL_ERROR)
                throw new HttpException(502, SampleErrorCode.UPMS_RESPONSE_INVALID);
            throw failure;
        }
    }
}
