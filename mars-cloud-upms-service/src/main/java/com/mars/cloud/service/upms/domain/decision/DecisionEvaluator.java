package com.mars.cloud.service.upms.domain.decision;

import com.mars.cloud.service.upms.domain.policy.Grant;
import com.mars.cloud.service.upms.domain.policy.PlatformRoleKey;
import com.mars.cloud.service.upms.domain.policy.RoleDefinition;
import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
import com.mars.cloud.service.upms.domain.snapshot.PlatformRegistry;
import org.springframework.stereotype.Component;

@Component
public class DecisionEvaluator {

    public DecisionOutcome evaluate(DecisionSnapshot snapshot, String callerId, String action, ResourceId resource) {
        PlatformRegistry registry = snapshot.registry(resource.platform()).orElse(null);
        if (registry == null) {
            return DecisionOutcome.deny("unregistered_platform");
        }
        if (!registry.hasAction(action)) {
            return DecisionOutcome.deny("unregistered_action");
        }
        if (!registry.hasResource(resource.localId())) {
            return DecisionOutcome.deny("unregistered_resource");
        }
        for (PlatformRoleKey roleKey : snapshot.rolesForSubject(callerId)) {
            RoleDefinition role = snapshot.role(roleKey).orElse(null);
            if (role == null) {
                continue;
            }
            for (Grant grant : role.grants()) {
                if (matches(grant, resource, action)) {
                    return DecisionOutcome.allow();
                }
            }
        }
        return DecisionOutcome.deny("no_grant");
    }

    private static boolean matches(Grant grant, ResourceId resource, String action) {
        return grant.platform().equals(resource.platform())
                && grant.action().equals(action)
                && grant.resource().equals(resource.localId());
    }
}
