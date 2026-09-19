package com.mars.cloud.service.upms.domain.decision;

import com.mars.cloud.service.upms.domain.policy.Grant;
import com.mars.cloud.service.upms.domain.policy.PlatformRoleKey;
import com.mars.cloud.service.upms.domain.policy.RoleDefinition;
import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
import com.mars.cloud.service.upms.domain.snapshot.PlatformRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionEvaluatorTest {

    private final CanonicalValidator canonicalValidator = new CanonicalValidator();
    private final DecisionEvaluator evaluator = new DecisionEvaluator();

    @Test
    void matchingRoleGrantAllowsDecision() {
        DecisionOutcome outcome = evaluator.evaluate(
                snapshotWithRoles(
                        List.of(role("sinan", "Writer", grant("sinan", "write", "namespace/workload"))),
                        Map.of("caller-a", Set.of(new PlatformRoleKey("sinan", "Writer")))
                ),
                "caller-a",
                "write",
                resource("sinan:namespace/workload")
        );

        assertThat(outcome.decision()).isEqualTo("allow");
        assertThat(outcome.reasonCode()).isEqualTo("granted");
    }

    @Test
    void registeredTripleWithoutGrantDeniesNoGrant() {
        DecisionOutcome outcome = evaluator.evaluate(
                snapshotWithRoles(
                        List.of(role("sinan", "Writer", grant("sinan", "write", "namespace/workload"))),
                        Map.of("caller-b", Set.of(new PlatformRoleKey("sinan", "Writer")))
                ),
                "caller-a",
                "write",
                resource("sinan:namespace/workload")
        );

        assertThat(outcome.decision()).isEqualTo("deny");
        assertThat(outcome.reasonCode()).isEqualTo("no_grant");
    }

    @Test
    void unknownRegisteredStateDeniesWithExplicitReason() {
        DecisionSnapshot snapshot = snapshotWithRoles(List.of(), Map.of());

        assertThat(evaluator.evaluate(snapshot, "caller-a", "write", resource("unknown:namespace/workload")).reasonCode())
                .isEqualTo("unregistered_platform");
        assertThat(evaluator.evaluate(snapshot, "caller-a", "delete", resource("sinan:namespace/workload")).reasonCode())
                .isEqualTo("unregistered_action");
        assertThat(evaluator.evaluate(snapshot, "caller-a", "write", resource("sinan:namespace/other")).reasonCode())
                .isEqualTo("unregistered_resource");
    }

    @Test
    void grantResourceUsesLiteralMatchingOnly() {
        DecisionSnapshot snapshot = snapshotWithRoles(
                List.of(role("sinan", "WildcardLiteral", grant("sinan", "write", "workload:*"))),
                Map.of("caller-a", Set.of(new PlatformRoleKey("sinan", "WildcardLiteral")))
        );

        assertThat(evaluator.evaluate(snapshot, "caller-a", "write", resource("sinan:workload:*")).decision())
                .isEqualTo("allow");
        assertThat(evaluator.evaluate(snapshot, "caller-a", "write", resource("sinan:workload:a")).decision())
                .isEqualTo("deny");
    }

    @Test
    void unionOfRolesAllowsWhenAnyGrantMatchesAndPlatformsRemainIsolated() {
        DecisionSnapshot snapshot = snapshotWithRoles(
                List.of(
                        role("sinan", "Reader", grant("sinan", "read", "namespace/workload")),
                        role("sinan", "Writer", grant("sinan", "write", "namespace/workload")),
                        role("opsdeck", "Writer", grant("opsdeck", "write", "namespace/workload"))
                ),
                Map.of(
                        "caller-a", Set.of(
                                new PlatformRoleKey("sinan", "Reader"),
                                new PlatformRoleKey("sinan", "Writer")
                        ),
                        "caller-b", Set.of(new PlatformRoleKey("opsdeck", "Writer"))
                )
        );

        assertThat(evaluator.evaluate(snapshot, "caller-a", "write", resource("sinan:namespace/workload")).decision())
                .isEqualTo("allow");
        assertThat(evaluator.evaluate(snapshot, "caller-b", "write", resource("sinan:namespace/workload")).decision())
                .isEqualTo("deny");
    }

    private ResourceId resource(String value) {
        return ResourceId.parse(value, canonicalValidator);
    }

    private static DecisionSnapshot snapshotWithRoles(List<RoleDefinition> roles,
                                                      Map<String, Set<PlatformRoleKey>> bindings) {
        return new DecisionSnapshot(
                "active",
                List.of(
                        new PlatformRegistry(
                                "sinan",
                                Set.of("read", "write"),
                                Set.of("namespace/workload", "workload:*", "workload:a")
                        ),
                        new PlatformRegistry(
                                "opsdeck",
                                Set.of("write"),
                                Set.of("namespace/workload")
                        )
                ),
                roles,
                bindings
        );
    }

    private static RoleDefinition role(String platform, String name, Grant grant) {
        return new RoleDefinition(new PlatformRoleKey(platform, name), List.of(grant));
    }

    private static Grant grant(String platform, String action, String resource) {
        return new Grant(platform, action, resource);
    }
}
