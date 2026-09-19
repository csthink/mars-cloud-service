package com.mars.cloud.service.upms.application.service;

import com.mars.cloud.service.upms.application.dto.DecisionOutcome;
import com.mars.cloud.service.upms.domain.decision.CanonicalValidator;
import com.mars.cloud.service.upms.domain.decision.DecisionEvaluator;
import com.mars.cloud.service.upms.domain.decision.ResourceId;
import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
import com.mars.cloud.service.upms.infrastructure.audit.AuditSink;
import com.mars.cloud.service.upms.infrastructure.snapshot.ActiveSnapshotProvider;
import com.mars.cloud.service.upms.interfaces.dto.DecisionRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DecisionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionService.class);

    private final ActiveSnapshotProvider snapshotProvider;
    private final DecisionEvaluator evaluator;
    private final CanonicalValidator canonicalValidator;
    private final AuditSink auditSink;

    public DecisionService(ActiveSnapshotProvider snapshotProvider,
                           DecisionEvaluator evaluator,
                           CanonicalValidator canonicalValidator) {
        this(snapshotProvider, evaluator, canonicalValidator, AuditSink.noop());
    }

    @Autowired
    public DecisionService(ActiveSnapshotProvider snapshotProvider,
                           DecisionEvaluator evaluator,
                           CanonicalValidator canonicalValidator,
                           AuditSink auditSink) {
        this.snapshotProvider = snapshotProvider;
        this.evaluator = evaluator;
        this.canonicalValidator = canonicalValidator;
        this.auditSink = auditSink;
    }

    public DecisionOutcome decide(DecisionRequest request) {
        DecisionSnapshot snapshot = snapshotProvider.currentSnapshot();
        ResourceId resource = ResourceId.parse(request.resource(), canonicalValidator);
        com.mars.cloud.service.upms.domain.decision.DecisionOutcome outcome = evaluator.evaluate(
                snapshot,
                request.callerId(),
                request.action(),
                resource
        );
        String decisionId = UUID.randomUUID().toString();
        if ("deny".equals(outcome.decision())) {
            recordDenyAudit(request, outcome, snapshot, decisionId);
        }
        return DecisionOutcome.from(outcome, decisionId);
    }

    private void recordDenyAudit(DecisionRequest request,
                                 com.mars.cloud.service.upms.domain.decision.DecisionOutcome outcome,
                                 DecisionSnapshot snapshot,
                                 String decisionId) {
        try {
            auditSink.recordDenyDecision(
                    request.callerId(),
                    request.action(),
                    request.resource(),
                    outcome.reasonCode(),
                    snapshot.snapshotId(),
                    decisionId
            );
        } catch (RuntimeException ex) {
            LOGGER.warn("failed to record deny audit event for decision_id={}", decisionId, ex);
        }
    }
}
