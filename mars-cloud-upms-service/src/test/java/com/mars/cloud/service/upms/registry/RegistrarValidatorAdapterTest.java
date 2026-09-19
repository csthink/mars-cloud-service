package com.mars.cloud.service.upms.registry;

import com.mars.cloud.service.upms.domain.decision.CanonicalValidator;
import com.mars.cloud.service.upms.domain.policy.CapabilityItem;
import com.mars.cloud.service.upms.domain.policy.Grant;
import com.mars.cloud.service.upms.domain.policy.PlatformRoleKey;
import com.mars.cloud.service.upms.domain.policy.RoleDefinition;
import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
import com.mars.cloud.service.upms.infrastructure.audit.InMemoryAuditSink;
import com.mars.cloud.service.upms.infrastructure.registry.ActiveRegistryEvidence;
import com.mars.cloud.service.upms.infrastructure.registry.RegistrarValidatorAdapter;
import com.mars.cloud.service.upms.infrastructure.registry.RegistryDefinition;
import com.mars.cloud.service.upms.infrastructure.registry.RegistryFixtureLoader;
import com.mars.cloud.service.upms.infrastructure.registry.RegistryValidationContext;
import com.mars.cloud.service.upms.infrastructure.registry.RegistryValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrarValidatorAdapterTest {

    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
    private static final Duration DWELL = Duration.ofMinutes(5);

    private final InMemoryAuditSink auditSink = new InMemoryAuditSink();
    private final RegistrarValidatorAdapter validator = new RegistrarValidatorAdapter(new CanonicalValidator(), auditSink);
    private final RegistryFixtureLoader loader = new RegistryFixtureLoader();

    @Test
    void demoV1FixtureLoadsAndRegistersPlatformLocalCapabilities() {
        RegistryDefinition registry = demoV1();

        validator.validateInitial(registry);

        assertThat(registry.platform()).isEqualTo("demo");
        assertThat(registry.capsVer()).isEqualTo(1);
        assertThat(registry.toPlatformRegistry().resources()).contains(
                "view:domain:kubernetes-ops",
                "view:domain:observability",
                "view:domain:identity",
                "view:domain:tracing",
                "portal:admin"
        );
        assertThat(registry.item("view:domain:identity")).isPresent();
        assertThat(registry.item("view:domain:identity").orElseThrow().sensitive()).isTrue();
        assertThat(registry.item("view:domain:tracing").orElseThrow().deprecated()).isTrue();
    }

    @Test
    void validatorRejectsPureViewAndVersionViolations() {
        RegistryDefinition valid = demoV1();

        assertThatThrownBy(() -> validator.validateInitial(valid.withActions(List.of("view", "edit"))))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("pure view");
        assertThatThrownBy(() -> validator.validateInitial(valid.withActions(List.of())))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("actions");
        assertThatThrownBy(() -> validator.validateInitial(valid.withResources(List.of())))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("resources");
        assertThatThrownBy(() -> validator.validateInitial(valid.withResources(List.of(CapabilityItem.normal("view:domain:k8s-exec")))))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("forbidden operation token");
        assertThatThrownBy(() -> validator.validateInitial(valid.withCapsVer(0)))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("caps_ver");
        assertThatThrownBy(() -> validator.validateInitial(valid.withCapsVer(-1)))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("caps_ver");
        assertThatThrownBy(() -> loader.load(new ClassPathResource("fixtures/registries/demo-invalid-sensitivity.yml")))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("sensitivity");
        for (String forbidden : List.of(
                "view:domain:k8s-scale",
                "view:domain:logs",
                "view:domain:k8s-port-forward",
                "view:domain:k8s-patch",
                "view:domain:k8s-apply"
        )) {
            assertThatThrownBy(() -> validator.validateInitial(valid.withResources(List.of(CapabilityItem.normal(forbidden)))))
                    .isInstanceOf(RegistryValidationException.class)
                    .hasMessageContaining("forbidden operation token");
        }
        for (String malformed : List.of(
                "domain:observability",
                "view:domain:BadName",
                "view:domain:bad_name",
                "portal:Admin"
        )) {
            assertThatThrownBy(() -> validator.validateInitial(valid.withResources(List.of(CapabilityItem.normal(malformed)))))
                    .isInstanceOf(RegistryValidationException.class)
                    .hasMessageContaining("syntax");
        }
        assertThatThrownBy(() -> validator.validateTransition(
                valid,
                valid.withCapsVer(1).withContentHash("different-hash"),
                RegistryValidationContext.of(List.of(), NOW, DWELL, snapshotWithoutTracingGrant("candidate"))
        ))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("same caps_ver different hash");
        assertThatThrownBy(() -> validator.validateTransition(
                valid,
                valid.withResources(valid.resourcesWith(CapabilityItem.normal("view:domain:new-cap"))),
                RegistryValidationContext.of(List.of(), NOW, DWELL, snapshotWithoutTracingGrant("candidate"))
        ))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("content change must bump caps_ver");
        assertThatThrownBy(() -> validator.validateTransition(
                valid,
                valid.withCapsVer(2).withContentHash("demo-v2-fixture-hash"),
                RegistryValidationContext.of(List.of(), NOW, DWELL, snapshotWithDanglingGrant("candidate"))
        ))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("dangling grant");
    }

    @Test
    void lifecycleRemoveRequiresDeprecatedActiveEvidenceAndNoGrantReferences() {
        RegistryDefinition deprecated = demoV1()
                .withCapsVer(2)
                .withContentHash("demo-v2-deprecated")
                .withResources(demoV1().resourcesWith(
                        CapabilityItem.deprecated("view:domain:tracing")
                ));
        RegistryDefinition removed = deprecated
                .withCapsVer(3)
                .withContentHash("demo-v3-removed")
                .withoutResource("view:domain:tracing");
        ActiveRegistryEvidence activeEvidence = ActiveRegistryEvidence.loaded(
                "deprecated-active",
                NOW.minus(Duration.ofMinutes(10)),
                "fixture-validator",
                "fixture-validator-hash",
                Set.of("view:domain:tracing")
        );

        assertThatThrownBy(() -> validator.validateTransition(
                deprecated,
                removed,
                RegistryValidationContext.of(List.of(), NOW, DWELL, snapshotWithoutTracingGrant("candidate"))
        ))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("active evidence");
        assertThatThrownBy(() -> validator.validateTransition(
                deprecated,
                removed,
                RegistryValidationContext.of(List.of(activeEvidence), NOW, DWELL, snapshotWithTracingGrant("candidate"))
        ))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("still referenced");

        validator.validateTransition(
                deprecated,
                removed,
                RegistryValidationContext.of(List.of(activeEvidence), NOW, DWELL, snapshotWithoutTracingGrant("candidate"))
        );
    }

    @Test
    void lifecycleRemoveRejectsAcceptedButNeverActiveOrInsufficientDwellEvidence() {
        RegistryDefinition deprecated = demoV1()
                .withCapsVer(2)
                .withContentHash("demo-v2-deprecated")
                .withResources(demoV1().resourcesWith(CapabilityItem.deprecated("view:domain:tracing")));
        RegistryDefinition removed = deprecated
                .withCapsVer(3)
                .withContentHash("demo-v3-removed")
                .withoutResource("view:domain:tracing");

        assertThatThrownBy(() -> validator.validateTransition(
                deprecated,
                removed,
                RegistryValidationContext.of(
                        List.of(ActiveRegistryEvidence.acceptedOnly("accepted-only", Set.of("view:domain:tracing"))),
                        NOW,
                        DWELL,
                        snapshotWithoutTracingGrant("candidate")
                )
        ))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("active evidence");
        assertThatThrownBy(() -> validator.validateTransition(
                deprecated,
                removed,
                RegistryValidationContext.of(
                        List.of(ActiveRegistryEvidence.loaded(
                                "too-new",
                                NOW.minus(Duration.ofSeconds(30)),
                                "fixture-validator",
                                "fixture-validator-hash",
                                Set.of("view:domain:tracing")
                        )),
                        NOW,
                        DWELL,
                        snapshotWithoutTracingGrant("candidate")
                )
        ))
                .isInstanceOf(RegistryValidationException.class)
                .hasMessageContaining("dwell");
    }

    @Test
    void validatorRejectWritesSecurityEventWithoutReplacingHealthyActive() {
        RegistryDefinition invalid = demoV1().withActions(List.of("view", "edit"));

        assertThatThrownBy(() -> validator.validateInitial(invalid))
                .isInstanceOf(RegistryValidationException.class);

        assertThat(auditSink.events())
                .anySatisfy(event -> {
                    assertThat(event.type()).isEqualTo("security_event");
                    assertThat(event.fields()).containsEntry("event", "candidate_registry_rejected");
                });
    }

    private RegistryDefinition demoV1() {
        return loader.load(new ClassPathResource("fixtures/registries/demo-v1.yml"));
    }

    private static DecisionSnapshot snapshotWithTracingGrant(String snapshotId) {
        return snapshot(snapshotId, List.of(grant("view:domain:tracing")));
    }

    private static DecisionSnapshot snapshotWithoutTracingGrant(String snapshotId) {
        return snapshot(snapshotId, List.of(grant("view:domain:observability")));
    }

    private static DecisionSnapshot snapshotWithDanglingGrant(String snapshotId) {
        return snapshot(snapshotId, List.of(grant("view:domain:missing")));
    }

    private static DecisionSnapshot snapshot(String snapshotId, List<Grant> grants) {
        RegistryDefinition registry = new RegistryFixtureLoader()
                .load(new ClassPathResource("fixtures/registries/demo-v1.yml"));
        return new DecisionSnapshot(
                snapshotId,
                List.of(registry.toPlatformRegistry()),
                List.of(new RoleDefinition(new PlatformRoleKey("demo", "Ops"), grants)),
                Map.of("ops-user", Set.of(new PlatformRoleKey("demo", "Ops")))
        );
    }

    private static Grant grant(String resource) {
        return new Grant("demo", "view", resource);
    }
}
