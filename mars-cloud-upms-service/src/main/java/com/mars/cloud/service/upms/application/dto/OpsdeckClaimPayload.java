package com.mars.cloud.service.upms.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 能力声明的载荷。
 *
 * <p>{@code uims_*} 这两个字段名是**既有的对外线格式**，属于上游快照提供方的契约，
 * 不是本服务的命名。改名会破坏消费方，故保留原样。
 */
public record OpsdeckClaimPayload(@JsonProperty("uims_opsdeck_caps_ver") int uimsCapsVer,
                                  @JsonProperty("uims_opsdeck_caps") List<String> uimsOpsdeckCaps) {

    public OpsdeckClaimPayload {
        uimsOpsdeckCaps = List.copyOf(uimsOpsdeckCaps);
    }
}
