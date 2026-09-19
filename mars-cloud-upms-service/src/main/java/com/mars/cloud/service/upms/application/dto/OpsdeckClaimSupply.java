package com.mars.cloud.service.upms.application.dto;

import com.mars.cloud.service.upms.domain.snapshot.SupplyFreshness;

import java.util.List;

public record OpsdeckClaimSupply(OpsdeckClaimPayload payload,
                                 SupplyFreshness freshness,
                                 List<String> expectedDegradation) {

    public OpsdeckClaimSupply {
        expectedDegradation = List.copyOf(expectedDegradation);
    }
}
