package com.mars.cloud.service.upms.domain.decision;

public record ResourceId(String fullId, String platform, String localId) {

    public static ResourceId parse(String resource, CanonicalValidator validator) {
        String canonical = validator.requireCanonical("resource", resource);
        int separator = canonical.indexOf(':');
        if (separator <= 0 || separator == canonical.length() - 1) {
            throw new MalformedResourceException("resource must be platform-prefixed");
        }
        String platform = canonical.substring(0, separator);
        String localId = canonical.substring(separator + 1);
        validator.requireCanonical("resource platform", platform);
        validator.requireCanonical("resource local id", localId);
        return new ResourceId(canonical, platform, localId);
    }
}
