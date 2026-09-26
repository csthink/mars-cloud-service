package com.mars.cloud.service.auth.infrastructure.session;

import java.time.Duration;

/** Publishes revoked session identifiers to the store the gateway consults after signature verification. */
public interface RevocationStore {
    String KEY_PREFIX = "mars:auth:revoked:sid:";
    /** Must cover the longest remaining lifetime of an access token. */
    Duration TTL = Duration.ofMinutes(15);

    /** Writes the marker for one session identifier and returns only after the store acknowledged the write. */
    void revoke(String sessionId);
}
