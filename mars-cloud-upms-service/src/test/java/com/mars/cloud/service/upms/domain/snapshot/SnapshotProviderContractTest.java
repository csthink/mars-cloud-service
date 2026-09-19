package com.mars.cloud.service.upms.domain.snapshot;

import com.mars.cloud.service.upms.domain.policy.Grant;
import com.mars.cloud.service.upms.domain.policy.PlatformRoleKey;
import com.mars.cloud.service.upms.domain.policy.RoleDefinition;
import com.mars.cloud.service.upms.infrastructure.error.SnapshotUnavailableSignal;
import com.mars.cloud.service.upms.infrastructure.snapshot.InMemorySnapshotProvider;
import com.mars.cloud.service.upms.infrastructure.snapshot.SnapshotValidationException;
import com.mars.cloud.service.upms.infrastructure.snapshot.SnapshotValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotProviderContractTest {

    private final SnapshotValidator validator = new SnapshotValidator();

    @Test
    void snapshotValidationRejectsDanglingBindingRole() {
        DecisionSnapshot snapshot = new DecisionSnapshot(
                "candidate",
                List.of(registry()),
                List.of(role("sinan", "Writer", grant("sinan", "write", "namespace/workload"))),
                Map.of("caller-a", Set.of(new PlatformRoleKey("sinan", "Missing")))
        );

        assertThatThrownBy(() -> validator.validate(snapshot))
                .isInstanceOf(SnapshotValidationException.class)
                .hasMessageContaining("unknown role");
    }

    @Test
    void snapshotValidationRejectsDanglingGrantActionOrResource() {
        DecisionSnapshot unknownAction = new DecisionSnapshot(
                "candidate-action",
                List.of(registry()),
                List.of(role("sinan", "Writer", grant("sinan", "delete", "namespace/workload"))),
                Map.of("caller-a", Set.of(new PlatformRoleKey("sinan", "Writer")))
        );
        DecisionSnapshot unknownResource = new DecisionSnapshot(
                "candidate-resource",
                List.of(registry()),
                List.of(role("sinan", "Writer", grant("sinan", "write", "namespace/other"))),
                Map.of("caller-a", Set.of(new PlatformRoleKey("sinan", "Writer")))
        );

        assertThatThrownBy(() -> validator.validate(unknownAction))
                .isInstanceOf(SnapshotValidationException.class)
                .hasMessageContaining("unknown action");
        assertThatThrownBy(() -> validator.validate(unknownResource))
                .isInstanceOf(SnapshotValidationException.class)
                .hasMessageContaining("unknown resource");
    }

    @Test
    void snapshotValidationRejectsRolePlatformMismatch() {
        DecisionSnapshot snapshot = new DecisionSnapshot(
                "candidate",
                List.of(registry()),
                List.of(role("sinan", "Writer", grant("opsdeck", "write", "namespace/workload"))),
                Map.of("caller-a", Set.of(new PlatformRoleKey("sinan", "Writer")))
        );

        assertThatThrownBy(() -> validator.validate(snapshot))
                .isInstanceOf(SnapshotValidationException.class)
                .hasMessageContaining("role platform");
    }

    @Test
    void providerReportsUnavailableWhenNoActiveSnapshotExists() {
        InMemorySnapshotProvider provider = new InMemorySnapshotProvider(validator);

        assertThatThrownBy(provider::currentSnapshot)
                .isInstanceOf(SnapshotUnavailableSignal.class);
    }

    @Test
    void invalidCandidateDoesNotReplaceHealthyActiveSnapshot() {
        InMemorySnapshotProvider provider = new InMemorySnapshotProvider(validator);
        DecisionSnapshot active = validSnapshot("active");
        DecisionSnapshot invalidCandidate = new DecisionSnapshot(
                "invalid",
                List.of(registry()),
                List.of(role("sinan", "Writer", grant("sinan", "delete", "namespace/workload"))),
                Map.of("caller-a", Set.of(new PlatformRoleKey("sinan", "Writer")))
        );

        provider.publishCandidate(active);
        assertThatThrownBy(() -> provider.publishCandidate(invalidCandidate))
                .isInstanceOf(SnapshotValidationException.class);

        assertThat(provider.currentSnapshot().snapshotId()).isEqualTo("active");
    }

    @Test
    void providerCanRepresentActiveCorruptedAsSnapshotUnavailable() {
        InMemorySnapshotProvider provider = new InMemorySnapshotProvider(validator);
        provider.publishCandidate(validSnapshot("active"));

        provider.markActiveCorrupted();

        assertThatThrownBy(provider::currentSnapshot)
                .isInstanceOf(SnapshotUnavailableSignal.class);
    }

    private static DecisionSnapshot validSnapshot(String snapshotId) {
        return new DecisionSnapshot(
                snapshotId,
                List.of(registry()),
                List.of(role("sinan", "Writer", grant("sinan", "write", "namespace/workload"))),
                Map.of("caller-a", Set.of(new PlatformRoleKey("sinan", "Writer")))
        );
    }

    private static PlatformRegistry registry() {
        return new PlatformRegistry(
                "sinan",
                Set.of("write"),
                Set.of("namespace/workload")
        );
    }

    private static RoleDefinition role(String platform, String name, Grant grant) {
        return new RoleDefinition(new PlatformRoleKey(platform, name), List.of(grant));
    }

    private static Grant grant(String platform, String action, String resource) {
        return new Grant(platform, action, resource);
    }
}
