package com.mars.cloud.service.upms.infrastructure.audit;

import java.time.Instant;
import java.util.Map;

public interface AuditSink {

    void recordGrantMutation(String actor, String beforeSnapshotId, String afterSnapshotId);

    void recordSecurityEvent(String event, Map<String, String> fields);

    void recordDenyDecision(String callerId,
                            String action,
                            String resource,
                            String reasonCode,
                            String snapshotId,
                            String decisionId);

    static AuditSink noop() {
        return new NoopAuditSink();
    }

    record AuditEvent(String type, Instant occurredAt, Map<String, String> fields) {

        public AuditEvent {
            fields = Map.copyOf(fields);
        }
    }

    final class NoopAuditSink implements AuditSink {

        @Override
        public void recordGrantMutation(String actor, String beforeSnapshotId, String afterSnapshotId) {
        }

        @Override
        public void recordSecurityEvent(String event, Map<String, String> fields) {
        }

        @Override
        public void recordDenyDecision(String callerId,
                                       String action,
                                       String resource,
                                       String reasonCode,
                                       String snapshotId,
                                       String decisionId) {
        }
    }
}
