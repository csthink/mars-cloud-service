package com.mars.cloud.service.upms.application.service;

import com.mars.cloud.service.upms.application.dto.ClaimPayload;
import com.mars.cloud.service.upms.application.dto.ClaimSupply;
import com.mars.cloud.service.upms.domain.decision.CanonicalValidator;
import com.mars.cloud.service.upms.domain.decision.DecisionEvaluator;
import com.mars.cloud.service.upms.domain.decision.ResourceId;
import com.mars.cloud.service.upms.domain.policy.CapabilityItem;
import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
import com.mars.cloud.service.upms.domain.snapshot.PlatformRegistry;
import com.mars.cloud.service.upms.domain.snapshot.SupplyFreshness;
import com.mars.cloud.service.upms.infrastructure.registry.RegistryDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ClaimFixtureService {

    private final DecisionEvaluator evaluator = new DecisionEvaluator();
    private final CanonicalValidator canonicalValidator = new CanonicalValidator();

    public ClaimPayload healthyPayload(DecisionSnapshot snapshot,
                                              RegistryDefinition registry,
                                              String subject) {
        requireMatchesActiveSnapshot(snapshot, registry);
        return new ClaimPayload(registry.capsVer(), allowedCapabilities(snapshot, registry, subject, false).caps());
    }

    public ClaimSupply stalePayload(DecisionSnapshot lastKnown,
                                           RegistryDefinition registry,
                                           String subject,
                                           SupplyFreshness freshness) {
        requireMatchesActiveSnapshot(lastKnown, registry);
        AllowedCapabilities allowed = allowedCapabilities(lastKnown, registry, subject, true);
        return new ClaimSupply(
                new ClaimPayload(registry.capsVer(), allowed.caps()),
                freshness.withSensitiveDropped(allowed.sensitiveDropped()),
                List.of("expected fail-stale", "expected fail-closed")
        );
    }

    private AllowedCapabilities allowedCapabilities(DecisionSnapshot snapshot,
                                                    RegistryDefinition registry,
                                                    String subject,
                                                    boolean dropSensitive) {
        List<String> caps = new ArrayList<>();
        int dropped = 0;
        for (CapabilityItem item : registry.resources()) {
            if (!"allow".equals(decision(snapshot, registry.platform(), subject, item.id()))) {
                continue;
            }
            if (dropSensitive && item.sensitive()) {
                dropped++;
                continue;
            }
            caps.add(item.id());
        }
        return new AllowedCapabilities(caps, dropped);
    }

    private String decision(DecisionSnapshot snapshot, String platform, String subject, String capability) {
        return evaluator.evaluate(
                snapshot,
                subject,
                "view",
                ResourceId.parse(platform + ":" + capability, canonicalValidator)
        ).decision();
    }

    private static void requireMatchesActiveSnapshot(DecisionSnapshot snapshot, RegistryDefinition registry) {
        PlatformRegistry active = snapshot.registry(registry.platform())
                .orElseThrow(() -> new IllegalStateException(
                        "active snapshot registry missing for platform: " + registry.platform()
                ));
        Set<String> registryActions = Set.copyOf(registry.actions());
        Set<String> registryResources = registry.resources().stream()
                .map(CapabilityItem::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean matches = active.capsVer() == registry.capsVer()
                && active.contentHash().equals(registry.contentHash())
                && active.actions().equals(registryActions)
                && active.resources().equals(registryResources);
        if (!matches) {
            throw new IllegalStateException("active snapshot registry does not match supplied registry: " + registry.platform());
        }
    }

    private record AllowedCapabilities(List<String> caps, int sensitiveDropped) {

        private AllowedCapabilities {
            caps = List.copyOf(caps);
        }
    }
}
