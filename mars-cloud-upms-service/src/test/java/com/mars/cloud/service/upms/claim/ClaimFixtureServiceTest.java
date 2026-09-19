package com.mars.cloud.service.upms.claim;

import com.mars.cloud.service.upms.application.dto.ClaimPayload;
import com.mars.cloud.service.upms.application.dto.ClaimSupply;
import com.mars.cloud.service.upms.application.service.ClaimFixtureService;
import com.mars.cloud.service.upms.application.service.SupplyFreshnessEvaluator;
import com.mars.cloud.service.upms.domain.decision.CanonicalValidator;
import com.mars.cloud.service.upms.domain.decision.DecisionEvaluator;
import com.mars.cloud.service.upms.domain.decision.ResourceId;
import com.mars.cloud.service.upms.domain.policy.Grant;
import com.mars.cloud.service.upms.domain.policy.PlatformRoleKey;
import com.mars.cloud.service.upms.domain.policy.RoleDefinition;
import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
import com.mars.cloud.service.upms.domain.snapshot.SupplyFreshness;
import com.mars.cloud.service.upms.infrastructure.registry.RegistryDefinition;
import com.mars.cloud.service.upms.infrastructure.registry.RegistryFixtureLoader;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimFixtureServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");

    private final RegistryDefinition registry = new RegistryFixtureLoader()
            .load(new ClassPathResource("fixtures/registries/demo-v1.yml"));
    private final ClaimFixtureService claimService = new ClaimFixtureService();
    private final DecisionEvaluator evaluator = new DecisionEvaluator();
    private final CanonicalValidator canonicalValidator = new CanonicalValidator();
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void healthyPayloadIsBidirectionallyConsistentWithPdpDecision() {
        DecisionSnapshot snapshot = activeSnapshot("healthy");

        ClaimPayload payload = claimService.healthyPayload(snapshot, registry, "ops-admin");

        assertThat(payload.uimsCapsVer()).isEqualTo(1);
        assertThat(payload.uimsCaps()).contains(
                "view:domain:observability",
                "view:domain:identity",
                "view:domain:tracing",
                "portal:admin"
        );
        for (String capability : payload.uimsCaps()) {
            assertThat(pdpDecision(snapshot, "ops-admin", capability)).isEqualTo("allow");
        }
        assertThat(pdpDecision(snapshot, "ops-admin", "view:domain:delivery")).isEqualTo("deny");
    }

    @Test
    void healthyPayloadKeepsExplicitEmptyArrayForSubjectWithoutGrant() {
        ClaimPayload payload = claimService.healthyPayload(activeSnapshot("healthy-empty"), registry, "ops-outsider");

        assertThat(payload.uimsCaps()).isEmpty();
        assertThat(payload.uimsCapsVer()).isEqualTo(1);
    }

    @Test
    void payloadSerializesFrozenSnakeCaseClaimFields() throws Exception {
        ClaimPayload healthy = claimService.healthyPayload(activeSnapshot("json-healthy"), registry, "ops-admin");
        ClaimPayload empty = claimService.healthyPayload(activeSnapshot("json-empty"), registry, "ops-outsider");

        assertThat(objectMapper.writeValueAsString(healthy))
                .contains("\"uims_caps_ver\":1")
                .contains("\"uims_caps\"")
                .doesNotContain("uimsCapsVer")
                .doesNotContain("uimsCaps");
        assertThat(objectMapper.writeValueAsString(empty))
                .contains("\"uims_caps_ver\":1")
                .contains("\"uims_caps\":[]")
                .doesNotContain("uimsCapsVer")
                .doesNotContain("uimsCaps");
    }

    @Test
    void healthyPayloadRejectsRegistryThatDoesNotMatchActiveSnapshot() {
        RegistryDefinition mismatched = registry
                .withCapsVer(2)
                .withContentHash("demo-v2-other");

        assertThatThrownBy(() -> claimService.healthyPayload(activeSnapshot("healthy-mismatch"), mismatched, "ops-admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active snapshot registry");
    }

    @Test
    void stalePayloadComesFromLastKnownAndDropsSensitiveCapabilities() {
        DecisionSnapshot lastKnown = activeSnapshot("last-known");
        DecisionSnapshot current = snapshotWithoutAdminAndIdentity("current");
        SupplyFreshness freshness = new SupplyFreshnessEvaluator().evaluate(
                lastKnown.snapshotId(),
                current.snapshotId(),
                registry.capsVer(),
                NOW.minus(Duration.ofMinutes(20)),
                NOW,
                Duration.ofMinutes(5)
        );

        ClaimSupply supply = claimService.stalePayload(lastKnown, registry, "ops-admin", freshness);

        assertThat(supply.payload().uimsCaps())
                .contains("view:domain:observability", "view:domain:tracing")
                .doesNotContain("view:domain:identity", "portal:admin");
        assertThat(supply.freshness().state()).isEqualTo(SupplyFreshness.State.STALE);
        assertThat(supply.freshness().sourceSnapshotId()).isEqualTo("last-known");
        assertThat(supply.freshness().sensitiveDropped()).isEqualTo(2);
        assertThat(supply.expectedDegradation()).contains("expected fail-stale", "expected fail-closed");
    }

    @Test
    void deprecatedSensitiveCrossingIsHealthyVisibleAndStaleDropped() {
        RegistryDefinition sensitiveDeprecated = registry.withResources(registry.resourcesWith(
                com.mars.cloud.service.upms.domain.policy.CapabilityItem.sensitiveDeprecated("view:domain:tracing")
        ));
        DecisionSnapshot snapshot = activeSnapshot("deprecated-sensitive");
        SupplyFreshness stale = new SupplyFreshness(
                snapshot.snapshotId(),
                "new-active",
                sensitiveDeprecated.capsVer(),
                SupplyFreshness.State.STALE,
                0
        );

        assertThat(claimService.healthyPayload(snapshot, sensitiveDeprecated, "ops-admin").uimsCaps())
                .contains("view:domain:tracing");
        assertThat(claimService.stalePayload(snapshot, sensitiveDeprecated, "ops-admin", stale).payload().uimsCaps())
                .doesNotContain("view:domain:tracing");
    }

    private String pdpDecision(DecisionSnapshot snapshot, String subject, String capability) {
        return evaluator.evaluate(
                snapshot,
                subject,
                "view",
                ResourceId.parse("demo:" + capability, canonicalValidator)
        ).decision();
    }

    private DecisionSnapshot activeSnapshot(String snapshotId) {
        return new DecisionSnapshot(
                snapshotId,
                List.of(registry.toPlatformRegistry()),
                List.of(
                        role("Dev", List.of(grant("view:domain:kubernetes-ops"), grant("view:domain:observability"))),
                        role("Ops", List.of(grant("view:domain:observability"), grant("view:domain:tracing"))),
                        role("Admin", List.of(
                                grant("view:domain:observability"),
                                grant("view:domain:identity"),
                                grant("view:domain:tracing"),
                                grant("portal:admin")
                        ))
                ),
                Map.of(
                        "ops-dev", Set.of(new PlatformRoleKey("demo", "Dev")),
                        "ops-admin", Set.of(new PlatformRoleKey("demo", "Admin"))
                )
        );
    }

    private DecisionSnapshot snapshotWithoutAdminAndIdentity(String snapshotId) {
        return new DecisionSnapshot(
                snapshotId,
                List.of(registry.toPlatformRegistry()),
                List.of(role("Ops", List.of(grant("view:domain:observability")))),
                Map.of("ops-admin", Set.of(new PlatformRoleKey("demo", "Ops")))
        );
    }

    private static RoleDefinition role(String name, List<Grant> grants) {
        return new RoleDefinition(new PlatformRoleKey("demo", name), grants);
    }

    private static Grant grant(String resource) {
        return new Grant("demo", "view", resource);
    }
}
