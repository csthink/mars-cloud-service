package com.mars.cloud.service.gateway.web;

import com.mars.cloud.service.gateway.error.GatewayErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 一次异常解析的结果：响应状态、信封里的数字码、文案兜底与调试详情。
 *
 * @param status      HTTP 状态
 * @param code        信封里的数字码。网关自产错误取 {@link GatewayErrorCode}；无法归类的异常取 HTTP 状态码本身
 * @param fallbackMessage i18n 未命中时的文案
 * @param detail      给开发环境 {@code result.detail} 用的原始信息，可能为空
 */
public record ResolvedError(HttpStatusCode status, int code, String fallbackMessage, String detail) {

    static ResolvedError of(GatewayErrorCode errorCode, String detail) {
        return new ResolvedError(errorCode.getHttpStatus(), errorCode.getCode(), errorCode.name(), detail);
    }

    static ResolvedError ofStatus(HttpStatusCode status, String detail) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String fallback = resolved == null ? String.valueOf(status.value()) : resolved.getReasonPhrase();
        return new ResolvedError(status, status.value(), fallback, detail);
    }

    /**
     * 是否「无法归类」的错误（码为 500）。只有这类才值得带堆栈打 ERROR；
     * 目标服务不在、连不上、超时都是运行态的正常事件，逐请求打堆栈只会淹没真正的缺陷。
     */
    public boolean isUnclassified() {
        return code == HttpStatus.INTERNAL_SERVER_ERROR.value();
    }
}
