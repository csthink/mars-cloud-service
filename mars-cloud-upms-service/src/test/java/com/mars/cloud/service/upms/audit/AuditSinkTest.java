package com.mars.cloud.service.upms.audit;

import com.mars.cloud.service.upms.application.service.DecisionService;
import com.mars.cloud.service.upms.domain.decision.CanonicalValidator;
import com.mars.cloud.service.upms.domain.decision.DecisionEvaluator;
import com.mars.cloud.service.upms.domain.policy.Grant;
import com.mars.cloud.service.upms.domain.policy.PlatformRoleKey;
import com.mars.cloud.service.upms.domain.policy.RoleDefinition;
import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
import com.mars.cloud.service.upms.domain.snapshot.PlatformRegistry;
import com.mars.cloud.service.upms.infrastructure.audit.AuditSink;
import com.mars.cloud.service.upms.infrastructure.audit.InMemoryAuditSink;
import com.mars.cloud.service.upms.infrastructure.snapshot.InMemorySnapshotProvider;
import com.mars.cloud.service.upms.infrastructure.snapshot.SnapshotValidator;
import com.mars.cloud.service.upms.interfaces.dto.DecisionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSinkTest {

    private final InMemoryAuditSink auditSink = new InMemoryAuditSink();

    @Test
    void auditSinkRecordsGrantMutationAndSecurityEventSeams() {
        auditSink.recordGrantMutation("actor-mars", "before-a", "after-b");
        auditSink.recordSecurityEvent("injected_mode_startup", Map.of("profile", "dev"));

        assertThat(auditSink.events()).extracting("type")
                .containsExactly("grant_mutation", "security_event");
        assertThat(auditSink.events().get(0).fields())
                .containsEntry("actor", "actor-mars")
                .containsEntry("before_snapshot_id", "before-a")
                .containsEntry("after_snapshot_id", "after-b");
        assertThat(auditSink.events().get(1).fields())
                .containsEntry("event", "injected_mode_startup")
                .containsEntry("profile", "dev");
    }

    @Test
    void decisionServiceAuditsDenyButDoesNotFullAuditAllowByDefault() {
        InMemorySnapshotProvider provider = new InMemorySnapshotProvider(new SnapshotValidator());
        provider.publishCandidate(snapshot("audit-snapshot"));
        DecisionService service = new DecisionService(
                provider,
                new DecisionEvaluator(),
                new CanonicalValidator(),
                auditSink
        );

        service.decide(new DecisionRequest("caller-b", "write", "sinan:namespace/workload"));
        service.decide(new DecisionRequest("caller-a", "write", "sinan:namespace/workload"));

        assertThat(auditSink.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("deny_decision");
            assertThat(event.fields())
                    .containsEntry("caller_id", "caller-b")
                    .containsEntry("action", "write")
                    .containsEntry("resource", "sinan:namespace/workload")
                    .containsEntry("reason_code", "no_grant")
                    .containsEntry("snapshot_id", "audit-snapshot");
            assertThat(event.fields().get("decision_id")).isNotBlank();
        });
    }

    @Test
    void denyDecisionStillReturnsWhenAuditSinkFails() {
        InMemorySnapshotProvider provider = new InMemorySnapshotProvider(new SnapshotValidator());
        provider.publishCandidate(snapshot("audit-snapshot"));
        DecisionService service = new DecisionService(
                provider,
                new DecisionEvaluator(),
                new CanonicalValidator(),
                throwingAuditSink()
        );

        com.mars.cloud.service.upms.application.dto.DecisionOutcome outcome =
                service.decide(new DecisionRequest("caller-b", "write", "sinan:namespace/workload"));

        assertThat(outcome.decision()).isEqualTo("deny");
        assertThat(outcome.reasonCode()).isEqualTo("no_grant");
        assertThat(outcome.decisionId()).isNotBlank();
    }

    private static DecisionSnapshot snapshot(String snapshotId) {
        return new DecisionSnapshot(
                snapshotId,
                List.of(new PlatformRegistry(
                        "sinan",
                        Set.of("write"),
                        Set.of("namespace/workload")
                )),
                List.of(new RoleDefinition(
                        new PlatformRoleKey("sinan", "Writer"),
                        List.of(new Grant("sinan", "write", "namespace/workload"))
                )),
                Map.of("caller-a", Set.of(new PlatformRoleKey("sinan", "Writer")))
        );
    }

    private static AuditSink throwingAuditSink() {
        return new AuditSink() {
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
                throw new IllegalStateException("audit sink down");
            }
        };
    }
}
