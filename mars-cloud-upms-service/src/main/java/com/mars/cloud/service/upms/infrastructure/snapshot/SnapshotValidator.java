package com.mars.cloud.service.upms.infrastructure.snapshot;

import com.mars.cloud.service.upms.domain.decision.CanonicalValidator;
import com.mars.cloud.service.upms.domain.policy.Grant;
import com.mars.cloud.service.upms.domain.policy.PlatformRoleKey;
import com.mars.cloud.service.upms.domain.policy.RoleDefinition;
import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
import com.mars.cloud.service.upms.domain.snapshot.PlatformRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SnapshotValidator {

    private final CanonicalValidator canonicalValidator;

    public SnapshotValidator() {
        this(new CanonicalValidator());
    }

    public SnapshotValidator(CanonicalValidator canonicalValidator) {
        this.canonicalValidator = canonicalValidator;
    }

    public void validate(DecisionSnapshot snapshot) {
        canonicalValidator.requireCanonical("snapshot id", snapshot.snapshotId());
        validateRegistries(snapshot);
        validateRoles(snapshot);
        validateBindings(snapshot);
    }

    private void validateRegistries(DecisionSnapshot snapshot) {
        for (PlatformRegistry registry : snapshot.registries().values()) {
            canonicalValidator.requireCanonical("platform", registry.platform());
            for (String action : registry.actions()) {
                canonicalValidator.requireCanonical("action", action);
            }
            for (String resource : registry.resources()) {
                canonicalValidator.requireCanonical("resource", resource);
            }
        }
    }

    private void validateRoles(DecisionSnapshot snapshot) {
        for (RoleDefinition role : snapshot.roles().values()) {
            canonicalValidator.requireCanonical("role platform", role.key().platform());
            canonicalValidator.requireCanonical("role name", role.key().roleName());
            PlatformRegistry roleRegistry = snapshot.registry(role.key().platform())
                    .orElseThrow(() -> new SnapshotValidationException("unknown role platform: " + role.key().platform()));
            for (Grant grant : role.grants()) {
                validateGrant(role, roleRegistry, grant);
            }
        }
    }

    private void validateGrant(RoleDefinition role, PlatformRegistry roleRegistry, Grant grant) {
        canonicalValidator.requireCanonical("grant platform", grant.platform());
        canonicalValidator.requireCanonical("grant action", grant.action());
        canonicalValidator.requireCanonical("grant resource", grant.resource());
        if (!role.key().platform().equals(grant.platform())) {
            throw new SnapshotValidationException("role platform does not match grant platform");
        }
        if (!roleRegistry.hasAction(grant.action())) {
            throw new SnapshotValidationException("unknown action in grant: " + grant.action());
        }
        if (!roleRegistry.hasResource(grant.resource())) {
            throw new SnapshotValidationException("unknown resource in grant: " + grant.resource());
        }
    }

    private void validateBindings(DecisionSnapshot snapshot) {
        for (Map.Entry<String, java.util.Set<PlatformRoleKey>> binding : snapshot.bindings().entrySet()) {
            canonicalValidator.requireCanonical("binding subject", binding.getKey());
            for (PlatformRoleKey key : binding.getValue()) {
                if (!snapshot.roles().containsKey(key)) {
                    throw new SnapshotValidationException("unknown role in binding: " + key.asString());
                }
            }
        }
    }
}
