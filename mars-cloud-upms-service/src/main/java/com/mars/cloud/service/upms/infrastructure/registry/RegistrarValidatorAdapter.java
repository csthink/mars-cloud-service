package com.mars.cloud.service.upms.infrastructure.registry;

import com.mars.cloud.service.upms.domain.decision.CanonicalValidator;
import com.mars.cloud.service.upms.domain.policy.CapabilityItem;
import com.mars.cloud.service.upms.domain.policy.Grant;
import com.mars.cloud.service.upms.domain.policy.RoleDefinition;
import com.mars.cloud.service.upms.infrastructure.audit.AuditSink;
import com.mars.cloud.service.upms.infrastructure.snapshot.SnapshotValidationException;
import com.mars.cloud.service.upms.infrastructure.snapshot.SnapshotValidator;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RegistrarValidatorAdapter {

    private static final Pattern FORBIDDEN_OPERATION_TOKEN =
            Pattern.compile("(^|[:/_-])(apply|create|delete|exec|logs|patch|port-forward|scale|shell|ssh|sudo|update|write)([:/_-]|$)");
    private static final Pattern OPSDECK_V1_RESOURCE =
            Pattern.compile("^(view:domain:[a-z0-9]+(?:-[a-z0-9]+)*|portal:[a-z0-9]+(?:-[a-z0-9]+)*)$");

    private final CanonicalValidator canonicalValidator;
    private final AuditSink auditSink;
    private final SnapshotValidator snapshotValidator;

    public RegistrarValidatorAdapter(CanonicalValidator canonicalValidator, AuditSink auditSink) {
        this(canonicalValidator, auditSink, new SnapshotValidator(canonicalValidator));
    }

    public RegistrarValidatorAdapter(CanonicalValidator canonicalValidator,
                                     AuditSink auditSink,
                                     SnapshotValidator snapshotValidator) {
        this.canonicalValidator = canonicalValidator;
        this.auditSink = auditSink;
        this.snapshotValidator = snapshotValidator;
    }

    public void validateInitial(RegistryDefinition registry) {
        try {
            requireInitial(registry);
        } catch (RegistryValidationException ex) {
            auditRejected(registry, ex);
            throw ex;
        }
    }

    public void validateTransition(RegistryDefinition previous,
                                   RegistryDefinition candidate,
                                   RegistryValidationContext context) {
        try {
            requireInitial(candidate);
            requireValidCandidateSnapshot(context);
            requireVersionProgression(previous, candidate);
            requireRemovalLifecycle(previous, candidate, context);
        } catch (RegistryValidationException ex) {
            auditRejected(candidate, ex);
            throw ex;
        }
    }

    private void requireInitial(RegistryDefinition registry) {
        canonicalValidator.requireCanonical("registry platform", registry.platform());
        canonicalValidator.requireCanonical("registry content hash", registry.contentHash());
        canonicalValidator.requireCanonical("registry syntax", registry.syntax());
        if (registry.capsVer() <= 0) {
            throw new RegistryValidationException("registry caps_ver must be positive");
        }
        if (registry.actions().isEmpty()) {
            throw new RegistryValidationException("registry actions must be non-empty");
        }
        if (registry.resources().isEmpty()) {
            throw new RegistryValidationException("registry resources must be non-empty");
        }
        if (!registry.actions().equals(java.util.List.of("view"))) {
            throw new RegistryValidationException("opsdeck registrar fixture must remain pure view");
        }
        Set<String> seenResources = new HashSet<>();
        for (String action : registry.actions()) {
            canonicalValidator.requireCanonical("registry action", action);
        }
        for (CapabilityItem item : registry.resources()) {
            canonicalValidator.requireCanonical("registry resource", item.id());
            if (!seenResources.add(item.id())) {
                throw new RegistryValidationException("duplicate registry resource: " + item.id());
            }
            requireSyntax(registry, item.id());
            if (FORBIDDEN_OPERATION_TOKEN.matcher(item.id()).find()) {
                throw new RegistryValidationException("forbidden operation token in registry resource: " + item.id());
            }
        }
    }

    private void requireSyntax(RegistryDefinition registry, String resource) {
        if ("opsdeck-v1".equals(registry.syntax())) {
            if (!OPSDECK_V1_RESOURCE.matcher(resource).matches()) {
                throw new RegistryValidationException("registry resource does not match opsdeck-v1 syntax: " + resource);
            }
            return;
        }
        throw new RegistryValidationException("unsupported registry syntax: " + registry.syntax());
    }

    private void requireValidCandidateSnapshot(RegistryValidationContext context) {
        try {
            snapshotValidator.validate(context.candidateSnapshot());
        } catch (SnapshotValidationException ex) {
            throw new RegistryValidationException("dangling grant in candidate snapshot: " + ex.getMessage());
        }
    }

    private void requireVersionProgression(RegistryDefinition previous, RegistryDefinition candidate) {
        if (candidate.capsVer() == previous.capsVer()
                && !candidate.contentHash().equals(previous.contentHash())) {
            throw new RegistryValidationException("same caps_ver different hash");
        }
        if (!sameRegistryContent(previous, candidate) && candidate.capsVer() <= previous.capsVer()) {
            throw new RegistryValidationException("content change must bump caps_ver");
        }
    }

    private void requireRemovalLifecycle(RegistryDefinition previous,
                                         RegistryDefinition candidate,
                                         RegistryValidationContext context) {
        Set<String> removed = removedResources(previous, candidate);
        for (String resource : removed) {
            CapabilityItem previousItem = previous.item(resource)
                    .orElseThrow(() -> new RegistryValidationException("removed resource missing previous item"));
            if (!previousItem.deprecated()) {
                throw new RegistryValidationException("removed resource lacks deprecated lifecycle marker: " + resource);
            }
            requireActiveEvidence(resource, context);
            requireNoGrantReferences(previous.platform(), resource, context);
        }
    }

    private void requireActiveEvidence(String resource, RegistryValidationContext context) {
        ActiveRegistryEvidence evidence = context.activeEvidence().stream()
                .filter(candidate -> candidate.loaded() && candidate.deprecatedCapabilities().contains(resource))
                .findFirst()
                .orElseThrow(() -> new RegistryValidationException("active evidence required before removing: " + resource));
        Duration activeFor = Duration.between(evidence.activatedAt(), context.now());
        if (activeFor.compareTo(context.dwell()) < 0) {
            throw new RegistryValidationException("deprecated active evidence dwell not satisfied: " + resource);
        }
    }

    private void requireNoGrantReferences(String platform,
                                          String resource,
                                          RegistryValidationContext context) {
        for (RoleDefinition role : context.candidateSnapshot().roles().values()) {
            for (Grant grant : role.grants()) {
                if (grant.platform().equals(platform) && grant.resource().equals(resource)) {
                    throw new RegistryValidationException("removed resource still referenced by grant: " + resource);
                }
            }
        }
    }

    private static boolean sameRegistryContent(RegistryDefinition previous, RegistryDefinition candidate) {
        return previous.platform().equals(candidate.platform())
                && previous.actions().equals(candidate.actions())
                && previous.resources().equals(candidate.resources());
    }

    private static Set<String> removedResources(RegistryDefinition previous, RegistryDefinition candidate) {
        Set<String> candidateIds = candidate.resources().stream()
                .map(CapabilityItem::id)
                .collect(Collectors.toSet());
        return previous.resources().stream()
                .map(CapabilityItem::id)
                .filter(id -> !candidateIds.contains(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void auditRejected(RegistryDefinition registry, RegistryValidationException ex) {
        auditSink.recordSecurityEvent("candidate_registry_rejected", Map.of(
                "platform", registry.platform(),
                "reason", ex.getMessage()
        ));
    }
}
