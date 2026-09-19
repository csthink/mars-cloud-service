package com.mars.cloud.service.upms.application.dto;

import com.mars.cloud.service.upms.domain.snapshot.SupplyFreshness;

import java.util.List;

public record ClaimSupply(ClaimPayload payload,
                                 SupplyFreshness freshness,
                                 List<String> expectedDegradation) {

    public ClaimSupply {
        expectedDegradation = List.copyOf(expectedDegradation);
    }
}
