package com.mars.cloud.service.upms.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record OpsdeckClaimPayload(@JsonProperty("uims_opsdeck_caps_ver") int uimsCapsVer,
                                  @JsonProperty("uims_opsdeck_caps") List<String> uimsOpsdeckCaps) {

    public OpsdeckClaimPayload {
        uimsOpsdeckCaps = List.copyOf(uimsOpsdeckCaps);
    }
}
