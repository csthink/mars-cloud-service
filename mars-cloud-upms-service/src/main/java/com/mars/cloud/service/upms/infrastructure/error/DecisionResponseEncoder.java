package com.mars.cloud.service.upms.infrastructure.error;

import com.mars.cloud.common.response.UnifyResponse;
import com.mars.cloud.service.upms.application.dto.DecisionOutcome;
import com.mars.cloud.service.upms.interfaces.dto.DecisionResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class DecisionResponseEncoder {

    public ResponseEntity<UnifyResponse<DecisionResult>> decision(DecisionOutcome outcome) {
        DecisionResult result = new DecisionResult(
                outcome.decision(),
                outcome.reasonCode(),
                outcome.decisionId()
        );
        return ResponseEntity.ok(UnifyResponse.success(result));
    }

    public ResponseEntity<UnifyResponse<Object>> error(HttpStatus status, UpmsProtocolCode code, String message) {
        UnifyResponse<Object> response = new UnifyResponse<>();
        response.setSuccess(false);
        response.setCode(code.code());
        response.setMessage(message);
        return ResponseEntity.status(status).body(response);
    }
}
