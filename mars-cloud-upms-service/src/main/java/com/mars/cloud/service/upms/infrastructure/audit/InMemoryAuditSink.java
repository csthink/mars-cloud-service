package com.mars.cloud.service.upms.infrastructure.audit;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class InMemoryAuditSink implements AuditSink {

    private final Clock clock;
    private final List<AuditEvent> events = new ArrayList<>();

    public InMemoryAuditSink() {
        this(Clock.systemUTC());
    }

    InMemoryAuditSink(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void recordGrantMutation(String actor, String beforeSnapshotId, String afterSnapshotId) {
        record("grant_mutation", Map.of(
                "actor", actor,
                "before_snapshot_id", beforeSnapshotId,
                "after_snapshot_id", afterSnapshotId
        ));
    }

    @Override
    public synchronized void recordSecurityEvent(String event, Map<String, String> fields) {
        Map<String, String> enriched = new LinkedHashMap<>();
        enriched.put("event", event);
        enriched.putAll(fields);
        record("security_event", enriched);
    }

    @Override
    public synchronized void recordDenyDecision(String callerId,
                                                String action,
                                                String resource,
                                                String reasonCode,
                                                String snapshotId,
                                                String decisionId) {
        record("deny_decision", Map.of(
                "caller_id", callerId,
                "action", action,
                "resource", resource,
                "reason_code", reasonCode,
                "snapshot_id", snapshotId,
                "decision_id", decisionId
        ));
    }

    public synchronized List<AuditEvent> events() {
        return List.copyOf(events);
    }

    private void record(String type, Map<String, String> fields) {
        events.add(new AuditEvent(type, Instant.now(clock), fields));
    }
}
