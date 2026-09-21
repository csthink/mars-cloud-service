package com.mars.cloud.service.upms.interfaces.controller;

import com.mars.cloud.common.response.UnifyResponse;
import com.mars.cloud.mvc.annotation.IgnoreResponseAnnotation;
import com.mars.cloud.service.upms.application.dto.DecisionOutcome;
import com.mars.cloud.service.upms.application.service.DecisionService;
import com.mars.cloud.service.upms.infrastructure.error.DecisionResponseEncoder;
import com.mars.cloud.service.upms.infrastructure.error.SnapshotUnavailableSignal;
import com.mars.cloud.service.upms.infrastructure.error.UpmsProtocolCode;
import com.mars.cloud.service.upms.interfaces.dto.DecisionRequest;
import com.mars.cloud.service.upms.interfaces.dto.DecisionRequestParser;
import com.mars.cloud.service.upms.interfaces.dto.DecisionResult;
import com.mars.cloud.service.upms.interfaces.dto.ProtocolRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@IgnoreResponseAnnotation
@RequestMapping("/v1/decision")
public class DecisionController {

    private final DecisionRequestParser parser;
    private final DecisionService decisionService;
    private final DecisionResponseEncoder encoder;

    public DecisionController(DecisionRequestParser parser,
                              DecisionService decisionService,
                              DecisionResponseEncoder encoder) {
        this.parser = parser;
        this.decisionService = decisionService;
        this.encoder = encoder;
    }

    @PostMapping
    public ResponseEntity<? extends UnifyResponse<?>> decide(@RequestBody(required = false) Map<String, Object> body) {
        try {
            DecisionRequest request = parser.parse(body);
            var caller = com.mars.cloud.security.AuthenticatedCaller.from(
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
            if (!caller.subject().equals(request.callerId()))
                throw new com.mars.cloud.security.SecurityFailure(com.mars.cloud.security.SecurityErrorCode.CALLER_MISMATCH);
            DecisionOutcome outcome = decisionService.decide(request);
            ResponseEntity<UnifyResponse<DecisionResult>> response = encoder.decision(outcome);
            return response;
        } catch (ProtocolRequestException ex) {
            return encoder.error(HttpStatus.BAD_REQUEST, ex.code(), ex.getMessage());
        } catch (SnapshotUnavailableSignal ex) {
            return encoder.error(HttpStatus.SERVICE_UNAVAILABLE, UpmsProtocolCode.SNAPSHOT_UNAVAILABLE, ex.getMessage());
        } catch (com.mars.cloud.security.SecurityFailure ex) {
            throw ex;
        } catch (Exception ex) {
            return encoder.error(HttpStatus.INTERNAL_SERVER_ERROR, UpmsProtocolCode.INTERNAL_ERROR, "internal error");
        }
    }
}
