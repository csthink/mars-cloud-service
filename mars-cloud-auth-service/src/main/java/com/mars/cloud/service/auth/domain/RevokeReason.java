package com.mars.cloud.service.auth.domain;

/** Why a device session stopped being usable; persisted in sys_session.revoke_reason. */
public enum RevokeReason {
    /** Evicted because the account already had the maximum number of devices. */
    DEVICE_LIMIT,
    /** The backing browser session or protocol authorization had already disappeared. */
    EXPIRED,
    /** Revoked by an operator through the management API. */
    ADMIN,
    /** Revoked because the account was disabled. */
    USER_DISABLED
}
