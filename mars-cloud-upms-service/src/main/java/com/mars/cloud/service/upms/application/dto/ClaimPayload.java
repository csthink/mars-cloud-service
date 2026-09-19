package com.mars.cloud.service.upms.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 能力声明的载荷。
 *
 * <p>{@code uims_*} 前缀是**既有的对外线格式**，属于上游快照提供方的契约，
 * 不是本服务的命名，故保留该前缀（本服务的包名与类名都不再带这个历史名字）。
 *
 * <p>本载荷**尚未对外发布**（服务仓没有发过版，也没有外部消费方），
 * 所以字段名里原有的内部平台名已一并清掉，**没有留兼容别名**。
 * 将来接真实上游时若对端仍用旧字段名，需要在反序列化侧补 {@code @JsonAlias}。
 */
public record ClaimPayload(@JsonProperty("uims_caps_ver") int uimsCapsVer,
                           @JsonProperty("uims_caps") List<String> uimsCaps) {

    public ClaimPayload {
        uimsCaps = List.copyOf(uimsCaps);
    }
}
